package io.confluent.kafka.schemaregistry.storage;

import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.kafka.schemaregistry.client.rest.entities.Schema;
import io.confluent.kafka.schemaregistry.exceptions.SchemaRegistryException;
import io.confluent.kafka.schemaregistry.utils.QualifiedSubject;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import static io.confluent.kafka.schemaregistry.utils.QualifiedSubject.WILDCARD;

public class PgStore {
  private Connection conn;

  public void init() throws SchemaRegistryException {
    String url = "jdbc:postgresql://localhost:5555/ewu";
    Properties props = new Properties();
    props.setProperty("user", "postgres");
    props.setProperty("password", "postgres");
    try {
      this.conn = DriverManager.getConnection(url, props);
      this.conn.setAutoCommit(false);
      this.conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
    } catch (SQLException e) {
      throw new SchemaRegistryException(e);
    }
  }

  public void commit() throws SQLException {
    conn.commit();
  }

  public void rollback() {
    try {
      conn.rollback();
    } catch (SQLException ex) {
      throw new RuntimeException(ex);
    }
  }

  public Schema lookupSchemaBySubject(QualifiedSubject qs, Schema schema,
                                      String subject, byte[] hash,
                                      boolean lookupDeletedSchema) throws SchemaRegistryException {
    ResultSet rs = null;
    PreparedStatement ps;

    try {
      StringBuilder sql = new StringBuilder();
      sql.append("SELECT s.id, s.version FROM contexts c ")
          .append("JOIN subjects sub on c.id = sub.context_id ")
          .append("JOIN schemas s on s.subject_id = sub.id ")
          .append("WHERE c.tenant = ? AND c.context = ? AND sub.subject = ? AND hash = ? ");
      if (!lookupDeletedSchema) {
        sql.append("AND NOT s.deleted");
      }
      ps = conn.prepareStatement(sql.toString());
      ps.setString(1, qs.getTenant());
      ps.setString(2, qs.getContext());
      ps.setString(3, qs.getSubject());
      ps.setBytes(4, hash);
      rs = ps.executeQuery();
      if (rs != null) {
        if (rs.next()) {
          Schema matchingSchema = schema.copy();
          matchingSchema.setSubject(subject);
          matchingSchema.setId(rs.getInt(1));
          matchingSchema.setVersion(rs.getInt(2));
          return matchingSchema;
        }
      }
    } catch (Exception e) {
      throw new SchemaRegistryException("LookupSchemaBySubject error", e);
    } finally {
      closeResultSet(rs);
    }

    return null;
  }

  public Schema getSubjectVersion(QualifiedSubject qs, int version,
                                  boolean lookupDeletedSchema) throws SchemaRegistryException {
    ResultSet rs = null;
    PreparedStatement ps;

    try {
      StringBuilder sql = new StringBuilder();
      sql.append("SELECT s.id, sub.subject, s.version, s.type, s.str, sub.id FROM contexts c ")
          .append("JOIN subjects sub ON c.id = sub.context_id ")
          .append("JOIN schemas s ON s.subject_id = sub.id ")
          .append("WHERE c.tenant = ? AND c.context = ? AND sub.subject = ? AND version = ? ");
      if (!lookupDeletedSchema) {
        sql.append("AND NOT s.deleted");
      }
      ps = conn.prepareStatement(sql.toString());
      ps.setString(1, qs.getTenant());
      ps.setString(2, qs.getContext());
      ps.setString(3, qs.getSubject());
      ps.setInt(4, version);
      rs = ps.executeQuery();
      if (rs != null) {
        if (rs.next()) {
          return populateSchema(rs);
        }
      }
    } catch (Exception e) {
      throw new SchemaRegistryException("LookupSchemaBySubject error", e);
    } finally {
      closeResultSet(rs);
    }

    return null;
  }

  public boolean subjectExists(QualifiedSubject qs, boolean lookupDeletedSchema)
      throws SchemaRegistryException {
    ResultSet rs = null;
    PreparedStatement ps;

    try {
      StringBuilder sql = new StringBuilder();
      sql.append("SELECT sub.id FROM contexts c ")
          .append("JOIN subjects sub ON c.id = sub.context_id ")
          .append("WHERE c.tenant = ? AND c.context = ? AND sub.subject = ? ");
      if (!lookupDeletedSchema) {
        sql.append("AND NOT deleted");
      }
      ps = conn.prepareStatement(sql.toString());
      ps.setString(1, qs.getTenant());
      ps.setString(2, qs.getContext());
      ps.setString(3, qs.getSubject());
      rs = ps.executeQuery();
      if (rs != null) {
        if (rs.next()) {
          return true;
        }
      }
    } catch (Exception e) {
      throw new SchemaRegistryException("SubjectExists error", e);
    } finally {
      closeResultSet(rs);
    }

    return false;
  }

  public Set<Integer> referencesSchema(QualifiedSubject qs, Optional<Integer> version)
      throws SchemaRegistryException {
    Set<Integer> set = new HashSet<>();
    ResultSet rs = null;
    PreparedStatement ps;

    try {
      StringBuilder sql = new StringBuilder();
      sql.append("SELECT r.schema_id FROM contexts c ")
          .append("JOIN subjects sub ON c.id = sub.context_id ")
          .append("JOIN schemas s ON s.subject_id = sub.id ")
          .append("JOIN refs r ON s.subject_id = r.ref_subject_id AND s.id = r.ref_schema_id ")
          .append("WHERE c.tenant = ? AND c.context = ? AND sub.subject = ? ");
      if (version.isPresent()) {
        sql.append("AND version = ? ");
      }
      ps = conn.prepareStatement(sql.toString());
      ps.setString(1, qs.getTenant());
      ps.setString(2, qs.getContext());
      ps.setString(3, qs.getSubject());
      if (version.isPresent()) {
        ps.setInt(4, version.get());
      }
      rs = ps.executeQuery();
      if (rs != null) {
        while (rs.next()) {
          set.add(rs.getInt(1));
        }
      }
    } catch (Exception e) {
      throw new SchemaRegistryException("ReferencesSchema error", e);
    } finally {
      closeResultSet(rs);
    }

    return set;
  }

  public void softDeleteSchema(QualifiedSubject qs, int version) throws SchemaRegistryException {
    PreparedStatement ps;

    try {
      StringBuilder sql = new StringBuilder();
      sql.append("UPDATE schemas SET deleted = true WHERE (subject_id, version) IN ")
          .append("(SELECT sub.id, ? FROM subjects sub ")
          .append("JOIN contexts c ON sub.context_id = c.id ")
          .append("WHERE c.tenant = ? AND c.context = ? AND sub.subject = ?) ");
      ps = conn.prepareStatement(sql.toString());
      ps.setInt(1, version);
      ps.setString(2, qs.getTenant());
      ps.setString(3, qs.getContext());
      ps.setString(4, qs.getSubject());
      ps.executeUpdate();
    } catch (Exception e) {
      throw new SchemaRegistryException("SoftDeleteSchema error", e);
    }
  }

  public void hardDeleteSchema(QualifiedSubject qs, int version) throws SchemaRegistryException {
    ResultSet rs = null;
    PreparedStatement ps;
    Integer subjectId = null, schemaId = null;

    try {
      StringBuilder sql = new StringBuilder();

      sql.append("SELECT sub.id, s.id FROM contexts c ")
          .append("JOIN subjects sub ON c.id = sub.context_id ")
          .append("JOIN schemas s ON s.subject_id = sub.id ")
          .append("WHERE c.tenant = ? AND c.context = ? AND sub.subject = ? AND version = ? ");
      ps = conn.prepareStatement(sql.toString());
      ps.setString(1, qs.getTenant());
      ps.setString(2, qs.getContext());
      ps.setString(3, qs.getSubject());
      ps.setInt(4, version);
      rs = ps.executeQuery();
      if (rs != null) {
        if (rs.next()) {
          subjectId = rs.getInt(1);
          schemaId = rs.getInt(2);
        }
      }

      if (subjectId == null)
        return;

      sql.setLength(0);
      sql.append("DELETE FROM refs WHERE subject_id = ? AND schema_id = ?");
      ps = conn.prepareStatement(sql.toString());
      ps.setInt(1, subjectId);
      ps.setInt(2, schemaId);
      ps.executeUpdate();

      sql.setLength(0);
      sql.append("DELETE FROM schemas WHERE subject_id = ? AND id = ? ");
      ps = conn.prepareStatement(sql.toString());
      ps.setInt(1, subjectId);
      ps.setInt(2, schemaId);
      ps.executeUpdate();
    } catch (Exception e) {
      throw new SchemaRegistryException("HardDeleteSchema error", e);
    } finally {
      closeResultSet(rs);
    }
  }

  public void softDeleteSubject(QualifiedSubject qs) throws SchemaRegistryException {
    ResultSet rs = null;
    PreparedStatement ps;
    Integer subjectId = null;

    try {
      StringBuilder sql = new StringBuilder();
      sql.append("SELECT sub.id FROM subjects sub ")
          .append("JOIN contexts c ON sub.context_id = c.id ")
          .append("WHERE c.tenant = ? AND c.context = ? AND sub.subject = ? ");
      ps = conn.prepareStatement(sql.toString());
      ps.setString(1, qs.getTenant());
      ps.setString(2, qs.getContext());
      ps.setString(3, qs.getSubject());
      rs = ps.executeQuery();
      if (rs != null) {
        if (rs.next()) {
          subjectId = rs.getInt(1);
        }
      }

      if (subjectId == null)
        return;

      sql.setLength(0);
      sql.append("UPDATE schemas SET deleted = true WHERE subject_id = ? ");
      ps = conn.prepareStatement(sql.toString());
      ps.setInt(1, subjectId);
      ps.executeUpdate();

      sql.setLength(0);
      sql.append("UPDATE subjects SET deleted = true WHERE id = ? ");
      ps = conn.prepareStatement(sql.toString());
      ps.setInt(1, subjectId);
      ps.executeUpdate();
    } catch (Exception e) {
      throw new SchemaRegistryException("SoftDeleteSubject error", e);
    } finally {
      closeResultSet(rs);
    }
  }

  public void hardDeleteSubject(QualifiedSubject qs) throws SchemaRegistryException {
    ResultSet rs = null;
    PreparedStatement ps;
    Integer subjectId = null;

    try {
      StringBuilder sql = new StringBuilder();

      sql.append("SELECT sub.id FROM contexts c ")
          .append("JOIN subjects sub ON c.id = sub.context_id ")
          .append("WHERE c.tenant = ? AND c.context = ? AND sub.subject = ? ");
      ps = conn.prepareStatement(sql.toString());
      ps.setString(1, qs.getTenant());
      ps.setString(2, qs.getContext());
      ps.setString(3, qs.getSubject());
      rs = ps.executeQuery();
      if (rs != null) {
        if (rs.next()) {
          subjectId = rs.getInt(1);
        }
      }

      if (subjectId == null)
        return;

      sql.setLength(0);
      sql.append("DELETE FROM refs WHERE subject_id = ? ");
      ps = conn.prepareStatement(sql.toString());
      ps.setInt(1, subjectId);
      ps.executeUpdate();

      sql.setLength(0);
      sql.append("DELETE FROM schemas WHERE subject_id = ? ");
      ps = conn.prepareStatement(sql.toString());
      ps.setInt(1, subjectId);
      ps.executeUpdate();

      sql.setLength(0);
      sql.append("DELETE FROM subjects WHERE id = ? ");
      ps = conn.prepareStatement(sql.toString());
      ps.setInt(1, subjectId);
      ps.executeUpdate();
    } catch (Exception e) {
      throw new SchemaRegistryException("HardDeleteSubject error", e);
    } finally {
      closeResultSet(rs);
    }
  }

  public List<Schema> getAllVersionsDesc(QualifiedSubject qs, boolean lookupDeletedSchema)
      throws SchemaRegistryException {
    List<Schema> list = new ArrayList<>();
    ResultSet rs = null;
    PreparedStatement ps;

    try {
      StringBuilder sql = new StringBuilder();
      sql.append("SELECT s.id, sub.subject, s.version, s.type, s.str, sub.id FROM contexts c ")
          .append("JOIN subjects sub on c.id = sub.context_id ")
          .append("JOIN schemas s on s.subject_id = sub.id ")
          .append("WHERE c.tenant = ? AND c.context = ? AND sub.subject = ? ");
      if (!lookupDeletedSchema) {
        sql.append("AND NOT s.deleted ");
      }
      sql.append("ORDER BY version DESC");
      ps = conn.prepareStatement(sql.toString());
      ps.setString(1, qs.getTenant());
      ps.setString(2, qs.getContext());
      ps.setString(3, qs.getSubject());
      rs = ps.executeQuery();
      if (rs != null) {
        while (rs.next()) {
          list.add(populateSchema(rs));
        }
      }
    } catch (Exception e) {
      throw new SchemaRegistryException("GetAllVersionsDesc error", e);
    } finally {
      closeResultSet(rs);
    }

    return list;
  }

  public List<io.confluent.kafka.schemaregistry.client.rest.entities.SchemaReference> getReferences(
      int subjectId, int schemaId) throws SchemaRegistryException {
    List<io.confluent.kafka.schemaregistry.client.rest.entities.SchemaReference> list
        = new ArrayList<>();
    ResultSet rs = null;
    PreparedStatement ps;

    try {
      StringBuilder sql = new StringBuilder();
      sql.append("SELECT r.name, sub.subject, s.version FROM refs r ")
          .append("JOIN schemas s ON r.ref_schema_id = s.id AND r.ref_subject_id = s.subject_id ")
          .append("JOIN subjects sub on r.ref_subject_id = sub.id ")
          .append("WHERE r.subject_id = ? AND r.schema_id = ? ");
      ps = conn.prepareStatement(sql.toString());
      ps.setInt(1, subjectId);
      ps.setInt(2, schemaId);
      rs = ps.executeQuery();
      if (rs != null) {
        while (rs.next()) {
          list.add(populateSchemaReference(rs));
        }
      }
    } catch (Exception e) {
      throw new SchemaRegistryException("LookupSchemaBySubject error", e);
    } finally {
      closeResultSet(rs);
    }

    return list;
  }

  public Schema getLatestSubjectVersion(QualifiedSubject qs)
      throws SchemaRegistryException {
    ResultSet rs = null;
    PreparedStatement ps;

    try {
      StringBuilder sql = new StringBuilder();
      sql.append("SELECT s.id, sub.subject, s.version, s.type, s.str, sub.id FROM contexts c ")
          .append("JOIN subjects sub on c.id = sub.context_id ")
          .append("JOIN schemas s on s.subject_id = sub.id ")
          .append("WHERE c.tenant = ? AND sub.subject = ? ");
      if (!qs.getContext().equals(WILDCARD)) {
        sql.append("AND c.context = ? ");
      }
      sql.append("ORDER BY s.version DESC LIMIT 1");
      ps = conn.prepareStatement(sql.toString());
      ps.setString(1, qs.getTenant());
      ps.setString(2, qs.getSubject());
      if (!qs.getContext().equals(WILDCARD)) {
        ps.setString(3, qs.getContext());
      }
      rs = ps.executeQuery();
      if (rs != null) {
        if (rs.next()) {
          return populateSchema(rs);
        }
      }
    } catch (Exception e) {
      throw new SchemaRegistryException("GetLatestSubjectVersion error", e);
    } finally {
      closeResultSet(rs);
    }

    return null;
  }

  public int getOrCreateContext(QualifiedSubject qs) throws SchemaRegistryException {
    ResultSet rs = null;
    PreparedStatement ps;
    int contextId = -1;

    try {
      StringBuilder sql = new StringBuilder();
      sql.append("SELECT id FROM contexts WHERE tenant = ? AND context = ?");
      ps = conn.prepareStatement(sql.toString());
      ps.setString(1, qs.getTenant());
      ps.setString(2, qs.getContext());
      rs = ps.executeQuery();
      if (rs != null) {
        while (rs.next()) {
          contextId = rs.getInt(1);
        }
      }

      if (contextId < 0) {
        sql.setLength(0);
        sql.append("INSERT INTO contexts VALUES (DEFAULT, ?, ?, ?) ")
            .append("RETURNING id");
        ps = conn.prepareStatement(sql.toString());
        ps.setString(1, qs.getTenant());
        ps.setString(2, qs.getContext());
        ps.setInt(3, 0);
        rs = ps.executeQuery();
        if (rs != null) {
          while (rs.next()) {
            contextId = rs.getInt(1);
          }
        }
      }
    } catch (Exception e) {
      throw new SchemaRegistryException("GetOrCreateContext error", e);
    } finally {
      closeResultSet(rs);
    }

    return contextId;
  }

  public int getOrCreateSubject(int contextId, QualifiedSubject qs) throws SchemaRegistryException {
    ResultSet rs = null;
    PreparedStatement ps;
    int subjectId = -1;

    try {
      StringBuilder sql = new StringBuilder();
      sql.append("SELECT id FROM subjects WHERE context_id = ? ")
          .append("AND subject = ?");
      ps = conn.prepareStatement(sql.toString());
      ps.setInt(1, contextId);
      ps.setString(2, qs.getSubject());
      rs = ps.executeQuery();
      if (rs != null) {
        while (rs.next()) {
          subjectId = rs.getInt(1);
        }
      }

      if (subjectId < 0) {
        sql.setLength(0);
        sql.append("INSERT INTO subjects VALUES (DEFAULT, ?, ?) ")
            .append("RETURNING id");
        ps = conn.prepareStatement(sql.toString());
        ps.setInt(1, contextId);
        ps.setString(2, qs.getSubject());
        rs = ps.executeQuery();
        if (rs != null) {
          while (rs.next()) {
            subjectId = rs.getInt(1);
          }
        }
      }
    } catch (Exception e) {
      throw new SchemaRegistryException("GetOrCreateSubject error", e);
    } finally {
      closeResultSet(rs);
    }

    return subjectId;
  }

  public int getMaxVersion(int subjectId) throws SchemaRegistryException {
    ResultSet rs = null;
    PreparedStatement ps;
    int version = 1;

    try {
      StringBuilder sql = new StringBuilder();
      sql.append("SELECT MAX(version) FROM schemas WHERE subject_id = ?");
      ps = conn.prepareStatement(sql.toString());
      ps.setInt(1, subjectId);
      rs = ps.executeQuery();
      if (rs != null) {
        while (rs.next()) {
          version = rs.getInt(1);
        }
      }
    } catch (Exception e) {
      throw new SchemaRegistryException("GetMaxVersion error", e);
    } finally {
      closeResultSet(rs);
    }

    return version;
  }

  public int createSchema(int contextId, int subjectId, int version,
                          ParsedSchema parsedSchema, byte[] hash) throws SchemaRegistryException {
    ResultSet rs = null;
    PreparedStatement ps;
    int schemaId = 0;
    try {
      StringBuilder sql = new StringBuilder();
      sql.append("UPDATE contexts SET schemas = schemas + 1 ")
          .append("WHERE id = ? ")
          .append("RETURNING schemas");
      ps = conn.prepareStatement(sql.toString());
      ps.setInt(1, contextId);
      rs = ps.executeQuery();
      if (rs != null) {
        while (rs.next()) {
          schemaId = rs.getInt(1);
        }
      }

      sql.setLength(0);
      sql.append("INSERT INTO schemas VALUES (?, ?, ?, ?, ?, ?, ?) ")
          .append("RETURNING id");
      ps = conn.prepareStatement(sql.toString());
      ps.setInt(1, schemaId);
      ps.setInt(2, subjectId);
      ps.setInt(3, version);
      ps.setString(4, parsedSchema.schemaType());
      ps.setString(5, parsedSchema.canonicalString());
      ps.setBytes(6, hash);
      ps.setBoolean(7, false);
      rs = ps.executeQuery();
      if (rs != null) {
        while (rs.next()) {
          schemaId = rs.getInt(1);
        }
      }

      for (io.confluent.kafka.schemaregistry.client.rest.entities.SchemaReference r :
          parsedSchema.references()) {
        sql.setLength(0);
        sql.append("INSERT INTO refs (subject_id, schema_id, name, ref_subject_id, ref_schema_id) ")
            .append("SELECT ?, ?, ?, sub.id, s.id ")
            .append("FROM subjects sub JOIN schemas s on sub.id = s.subject_id ")
            .append("WHERE sub.context_id = ? AND sub.subject = ? AND s.version = ? ");
        ps = conn.prepareStatement(sql.toString());
        ps.setInt(1, subjectId);
        ps.setInt(2, schemaId);
        ps.setString(3, r.getName());
        ps.setInt(4, contextId);
        ps.setString(5, QualifiedSubject.create("", r.getSubject()).getSubject());
        ps.setInt(6, r.getVersion());
        ps.executeUpdate();
      }
    } catch (Exception e) {
      throw new SchemaRegistryException("CreateSchema error", e);
    } finally {
      closeResultSet(rs);
    }

    return schemaId;
  }

  private Schema populateSchema(ResultSet rs) throws Exception {
    int id = rs.getInt(1);
    String subject = rs.getString(2);
    int version = rs.getInt(3);
    String type = rs.getString(4);
    String str = rs.getString(5);
    int subjectId = rs.getInt(6);
    // TODO not handling N + 1 problem
    return new Schema(subject, version, id, type, getReferences(subjectId, id), str);
  }

  private io.confluent.kafka.schemaregistry.client.rest.entities.SchemaReference
    populateSchemaReference(ResultSet rs) throws SQLException {
    String name = rs.getString(1);
    String subject = rs.getString(2);
    int version = rs.getInt(3);
    return new io.confluent.kafka.schemaregistry.client.rest.entities.SchemaReference(
        name, subject, version);
  }

  private void closeResultSet(ResultSet rs) {
      if (rs != null) {
        try {
          rs.close();
        } catch (SQLException ignored) {
          // ignore
        }
      }
  }
}
