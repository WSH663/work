package cn.accessible.travel;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AuditMigrationTest {
    @Test void expandingAuditTargetPreservesExistingAuditDataAndAcceptsFullSettingsKeys() throws Exception {
        String url="jdbc:h2:mem:audit-upgrade;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("5").load().migrate();
        try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.createStatement()){
            statement.executeUpdate("INSERT INTO audit_logs(actor_id,actor_name,action,target) VALUES (7,'原管理员','EXISTING_ACTION','原有审计对象')");
        }
        Flyway.configure().dataSource(url,"sa","").load().migrate();
        try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.createStatement()){
            try(var rows=statement.executeQuery("SELECT actor_id,actor_name,action,target,created_at FROM audit_logs")){
                assertThat(rows.next()).isTrue();assertThat(rows.getLong("actor_id")).isEqualTo(7);assertThat(rows.getString("actor_name")).isEqualTo("原管理员");assertThat(rows.getString("action")).isEqualTo("EXISTING_ACTION");assertThat(rows.getString("target")).isEqualTo("原有审计对象");assertThat(rows.getTimestamp("created_at")).isNotNull();assertThat(rows.next()).isFalse();
            }
            try(var insert=connection.prepareStatement("INSERT INTO audit_logs(actor_id,actor_name,action,target) VALUES (?,?,?,?)")){
                insert.setLong(1,7);insert.setString(2,"原管理员");insert.setString(3,"LONG_TARGET");insert.setString(4,"x".repeat(512));assertThat(insert.executeUpdate()).isEqualTo(1);
            }
        }
    }
}
