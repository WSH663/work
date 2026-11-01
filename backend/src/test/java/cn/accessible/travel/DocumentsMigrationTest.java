package cn.accessible.travel;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DocumentsMigrationTest {
    @Test void upgradeRetainsEveryLegacyArticleInOrderIncludingDisabledMediaWithoutChangingSource()throws Exception {
        String url="jdbc:h2:mem:documents-upgrade;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("8").load().migrate();long imageId,videoId;
        try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()) {
            s.executeUpdate("INSERT INTO content(kind,title,body,media_type,media_url,poster_url,link_url,sort_order,enabled) VALUES ('SERVICE','原有视频','视频说明','VIDEO','https://example.com/service.mp4','https://example.com/poster.jpg','/pages/booking/index?type=CHARTER',9,FALSE)",java.sql.Statement.RETURN_GENERATED_KEYS);
            try(var keys=s.getGeneratedKeys()){keys.next();videoId=keys.getLong(1);}
            s.executeUpdate("INSERT INTO content(kind,title,body,media_type,media_url,link_url,sort_order,enabled) VALUES ('SERVICE','原有图片','图片说明','IMAGE','https://example.com/image.jpg','/pages/agreement/index?kind=privacy',2,TRUE)",java.sql.Statement.RETURN_GENERATED_KEYS);
            try(var keys=s.getGeneratedKeys()){keys.next();imageId=keys.getLong(1);}
        }
        Flyway.configure().dataSource(url,"sa","").load().migrate();var mapper=new ObjectMapper();
        try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()) {
            try(var rows=s.executeQuery("SELECT draft_json,published_json,revision,published_revision,published_at FROM documents WHERE kind='service'")) {
                assertThat(rows.next()).isTrue();var document=mapper.readTree(rows.getString("draft_json"));assertThat(mapper.readTree(rows.getString("published_json"))).isEqualTo(document);
                var articles=document.path("sections");int imageIndex=-1,videoIndex=-1;
                for(int n=0;n<articles.size();n++){var article=articles.get(n);if(article.path("id").asText().equals("legacy-service-"+imageId))imageIndex=n;if(article.path("id").asText().equals("legacy-service-"+videoId))videoIndex=n;}
                assertThat(imageIndex).isPositive();assertThat(videoIndex).isGreaterThan(imageIndex);assertThat(articles.get(articles.size()-1).path("id").asText()).isEqualTo("service-contact");
                assertThat(articles.get(imageIndex).path("title").asText()).isEqualTo("原有图片");assertThat(articles.get(imageIndex).path("enabled").asBoolean()).isTrue();assertThat(articles.get(imageIndex).path("mediaUrl").asText()).isEqualTo("https://example.com/image.jpg");
                assertThat(articles.get(videoIndex).path("enabled").asBoolean()).isFalse();assertThat(articles.get(videoIndex).path("posterUrl").asText()).isEqualTo("https://example.com/poster.jpg");assertThat(articles.get(videoIndex).path("linkUrl").asText()).isEqualTo("/pages/booking/index?type=CHARTER");
                assertThat(rows.getLong("revision")).isEqualTo(1);assertThat(rows.getLong("published_revision")).isEqualTo(1);assertThat(rows.getTimestamp("published_at")).isNotNull();
            }
            try(var rows=s.executeQuery("SELECT COUNT(*) FROM content WHERE kind='SERVICE'")){rows.next();assertThat(rows.getInt(1)).isEqualTo(3);}
            try(var rows=s.executeQuery("SELECT enabled,media_url,poster_url FROM content WHERE id="+videoId)){rows.next();assertThat(rows.getBoolean(1)).isFalse();assertThat(rows.getString(2)).isEqualTo("https://example.com/service.mp4");assertThat(rows.getString(3)).isEqualTo("https://example.com/poster.jpg");}
        }
    }
}
