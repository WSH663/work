package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** One-time initialization, including legacy service articles. Reads never mutate documents. */
public class V9__editable_documents extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        var connection=context.getConnection();
        try(var ddl=connection.createStatement()) {
            ddl.execute("CREATE TABLE documents (kind VARCHAR(20) PRIMARY KEY, draft_json LONGTEXT NOT NULL, published_json LONGTEXT NOT NULL, revision BIGINT NOT NULL, published_revision BIGINT NOT NULL, updated_at TIMESTAMP NOT NULL, published_at TIMESTAMP NOT NULL)");
        }
        var json=new ObjectMapper();
        var now=Timestamp.valueOf(LocalDateTime.now());
        for(String kind:List.of("service","privacy","terms")) {
            ObjectNode document;
            try(InputStream source=getClass().getResourceAsStream("/documents/default-"+kind+".json")) {
                if(source==null)throw new IllegalStateException("Missing document defaults: "+kind);
                document=(ObjectNode)json.readTree(source);
            }
            if("service".equals(kind)) {
                ArrayNode sections=(ArrayNode)document.get("sections");
                int insertAt=sections.size()-1;
                try(var query=connection.prepareStatement("SELECT id,title,body,media_type,media_url,poster_url,link_url,enabled FROM content WHERE kind='SERVICE' ORDER BY sort_order,id");var rows=query.executeQuery()) {
                    while(rows.next()) {
                        ObjectNode section=json.createObjectNode();
                        section.put("id","legacy-service-"+rows.getLong("id"));
                        section.put("title",rows.getString("title"));
                        section.put("body",rows.getString("body"));
                        section.put("mediaType",rows.getString("media_type"));
                        section.put("mediaUrl",rows.getString("media_url"));
                        section.put("posterUrl",rows.getString("poster_url"));
                        section.put("linkUrl",rows.getString("link_url"));
                        section.putArray("actions");
                        section.put("enabled",rows.getBoolean("enabled"));
                        sections.insert(insertAt++,section);
                    }
                }
            }
            String serialized=json.writeValueAsString(document);
            try(var insert=connection.prepareStatement("INSERT INTO documents(kind,draft_json,published_json,revision,published_revision,updated_at,published_at) VALUES (?,?,?,1,1,?,?)")) {
                insert.setString(1,kind);insert.setString(2,serialized);insert.setString(3,serialized);insert.setTimestamp(4,now);insert.setTimestamp(5,now);insert.executeUpdate();
            }
        }
    }
}
