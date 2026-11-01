package cn.accessible.travel;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PhoneMigrationTest {
    @Test void existingContactNumbersRemainUnverifiedAfterMigration() throws Exception {
        String url="jdbc:h2:mem:phone-upgrade;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("6").load().migrate();
        try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.createStatement()) {
            statement.executeUpdate("INSERT INTO users(nickname,phone,invite_code) VALUES ('历史用户','13800138000','PHONE-MIGRATION')");
        }
        Flyway.configure().dataSource(url,"sa","").load().migrate();
        try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.createStatement();var rows=statement.executeQuery("SELECT phone,phone_verified,phone_country_code,phone_verified_at FROM users WHERE invite_code='PHONE-MIGRATION'")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("phone")).isEqualTo("13800138000");
            assertThat(rows.getBoolean("phone_verified")).isFalse();
            assertThat(rows.getString("phone_country_code")).isNull();
            assertThat(rows.getTimestamp("phone_verified_at")).isNull();
        }
    }
}
