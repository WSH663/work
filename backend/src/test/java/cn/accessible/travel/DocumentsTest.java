package cn.accessible.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"spring.profiles.active=dev","spring.datasource.url=jdbc:h2:mem:documents;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","app.admin-username=documents-test-admin","app.admin-password=DocumentsTest123!"})
@AutoConfigureMockMvc
@Transactional
class DocumentsTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired Db db;@Autowired Auth auth;
    String token;
    @BeforeEach void login() throws Exception {
        token=send(post("/api/auth/admin/login"),Map.of("username","documents-test-admin","password","DocumentsTest123!"),null,200).path("data").path("token").asText();
    }
    JsonNode send(MockHttpServletRequestBuilder request,Object body,String authToken,int expected) throws Exception {
        if(body!=null)request.contentType("application/json").content(json.writeValueAsString(body));
        if(authToken!=null)request.header("Authorization","Bearer "+authToken);
        return json.readTree(mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());
    }
    JsonNode state(String kind)throws Exception{return send(get("/api/admin/documents/"+kind),null,token,200).path("data");}
    JsonNode published(String kind)throws Exception{return send(get("/api/public/documents/"+kind),null,null,200).path("data");}
    ObjectNode document(JsonNode state){return state.path("draft").deepCopy();}
    ObjectNode first(ObjectNode document){return (ObjectNode)document.withArray("sections").get(0);}
    String roleToken(String role){long id=db.insert("INSERT INTO users(nickname,role,invite_code) VALUES (?,?,?)",role,role,auth.randomCode());return auth.loginResult(db.one("SELECT * FROM users WHERE id=?",id)).get("token").toString();}

    @Test void completeDefaultsAreInitializedAndLegacyReadOnlyContractStillWorks()throws Exception {
        assertThat(db.count("SELECT COUNT(*) FROM documents")).isEqualTo(3);
        assertThat(db.count("SELECT COUNT(*) FROM flyway_schema_history WHERE version='9' AND success=TRUE")).isEqualTo(1);
        JsonNode service=state("service");assertThat(service.path("revision").asLong()).isEqualTo(1);
        assertThat(service.path("draft").path("sections").size()).isGreaterThanOrEqualTo(7);
        assertThat(service.toString()).contains("{{serviceArea}}","{{advanceRule}}","{{bookingRangeRule}}","{{cancellationRule}}","预约用车服务说明");
        JsonNode privacy=state("privacy");assertThat(privacy.toString()).contains("账号与联系信息","预约与协助需求","位置与地点信息","支付与提现信息","推广关系与佣金","运行日志与消息","amapPrivacy","profile","passengers","addresses","contact");
        assertThat(privacy.path("publishedAt").asText()).isNotEmpty();
        assertThat(published("terms").path("document").path("sections").size()).isEqualTo(3);
        assertThat(send(get("/api/public/home"),null,null,200).path("data").path("services").size()).isPositive();
        assertThat(db.count("SELECT COUNT(*) FROM audit_logs WHERE action LIKE 'DOCUMENT_%'")).isZero();
    }
    @Test void draftIsIsolatedPublishingUsesSavedVersionAndRetriesAreIdempotent()throws Exception {
        JsonNode initial=state("service");JsonNode before=published("service");ObjectNode draft=document(initial);
        draft.put("title","新版服务说明");first(draft).put("body","新服务正文 {{serviceArea}}");
        JsonNode saved=send(post("/api/admin/documents/service"),Map.of("revision",1,"document",draft),token,200).path("data");
        assertThat(saved.path("revision").asLong()).isEqualTo(2);assertThat(saved.path("publishedRevision").asLong()).isEqualTo(1);
        assertThat(published("service")).isEqualTo(before);
        JsonNode result=send(post("/api/admin/documents/service/publish"),Map.of("revision",2),token,200).path("data");
        assertThat(result.path("publishedRevision").asLong()).isEqualTo(2);assertThat(result.path("published").path("title").asText()).isEqualTo("新版服务说明");
        assertThat(published("service").path("revision").asLong()).isEqualTo(2);assertThat(published("service").path("document").path("title").asText()).isEqualTo("新版服务说明");
        assertThat(db.count("SELECT COUNT(*) FROM audit_logs WHERE action='DOCUMENT_DRAFT_SAVE'")).isEqualTo(1);assertThat(db.count("SELECT COUNT(*) FROM audit_logs WHERE action='DOCUMENT_PUBLISH'")).isEqualTo(1);
        JsonNode retry=send(post("/api/admin/documents/service/publish"),Map.of("revision",2),token,200).path("data");
        assertThat(retry.path("publishedAt")).isEqualTo(result.path("publishedAt"));assertThat(db.count("SELECT COUNT(*) FROM audit_logs WHERE action='DOCUMENT_PUBLISH'")).isEqualTo(1);
        send(post("/api/admin/documents/service"),Map.of("revision",1,"document",draft),token,409);
        send(post("/api/admin/documents/service/publish"),Map.of("revision",1),token,409);
        assertThat(state("service").path("revision").asLong()).isEqualTo(2);
    }
    @Test void hiddenSectionsStayInTemplatesAndRulesResolveFromCurrentSettingsWithoutRepublishing()throws Exception {
        JsonNode initial=state("service");ObjectNode draft=document(initial);first(draft).put("enabled",false);
        send(post("/api/admin/documents/service"),Map.of("revision",1,"document",draft),token,200);
        send(post("/api/admin/documents/service/publish"),Map.of("revision",2),token,200);
        assertThat(state("service").path("published").path("sections").size()).isEqualTo(draft.path("sections").size());
        assertThat(published("service").path("document").path("sections").size()).isEqualTo(draft.path("sections").size()-1);
        db.update("UPDATE settings SET setting_value='90' WHERE setting_key='advanceMinutes'");db.update("UPDATE settings SET setting_value='14' WHERE setting_key='maxAdvanceDays'");db.update("UPDATE settings SET setting_value='1.5' WHERE setting_key='cancelHours'");
        JsonNode current=published("service");assertThat(current.toString()).contains("提前 90 分钟","未来 14 天","前 1.5 小时").doesNotContain("{{");
        assertThat(current.path("revision").asLong()).isEqualTo(2);assertThat(state("service").path("variables").path("cancelHours").asText()).isEqualTo("1.5");
        db.update("UPDATE settings SET setting_value='0' WHERE setting_key='advanceMinutes'");db.update("UPDATE settings SET setting_value='0' WHERE setting_key='cancelHours'");
        assertThat(published("service").toString()).contains("预约时间须晚于当前时间","预约时间前，可按订单详情提示填写原因");
    }
    @Test void administrationRequiresOperationalRoleAndAuditsItsActualActor()throws Exception {
        send(get("/api/admin/documents/privacy"),null,null,401);String finance=roleToken("FINANCE"),user=roleToken("USER"),operator=roleToken("OPERATOR");
        for(String denied:List.of(finance,user)) {
            send(get("/api/admin/documents/privacy"),null,denied,403);
            send(post("/api/admin/documents/privacy"),Map.of("revision",1,"document",document(state("privacy"))),denied,403);
            send(post("/api/admin/documents/privacy/publish"),Map.of("revision",1),denied,403);
        }
        send(get("/api/admin/documents/privacy"),null,operator,200);
        send(post("/api/admin/documents/privacy"),Map.of("revision",1,"document",document(state("privacy"))),operator,200);
        send(post("/api/admin/documents/privacy/publish"),Map.of("revision",2),operator,200);
        assertThat(db.list("SELECT actor_name FROM audit_logs WHERE action LIKE 'DOCUMENT_%'")).allMatch(row->"OPERATOR".equals(row.get("actorName")));
        send(get("/api/public/documents/unknown"),null,null,404);send(get("/api/admin/documents/unknown"),null,token,404);
    }
    @Test void invalidContentCannotChangeDraftOrPublicState()throws Exception {
        JsonNode initial=state("privacy");JsonNode publicBefore=published("privacy");
        List<java.util.function.Consumer<ObjectNode>> invalid=List.of(
            d->d.put("title",""),d->d.put("title","字".repeat(121)),d->first(d).put("body",""),d->first(d).put("body","字".repeat(8001)),
            d->first(d).put("id","INVALID ID"),d->((ObjectNode)d.withArray("sections").get(1)).put("id",first(d).path("id").asText()),
            d->d.putArray("sections"),d->d.put("introduction","{{unknown}}"),d->first(d).put("body","文本 {{serviceArea"),d->first(d).put("body","文本 serviceArea}}"),
            d->first(d).put("mediaType","HTML"),d->{first(d).put("mediaType","IMAGE");first(d).put("mediaUrl","http://example.com/image.jpg");},
            d->{first(d).put("mediaType","VIDEO");first(d).put("mediaUrl","https://example.com/video.mp4");first(d).put("posterUrl","javascript:alert(1)");},
            d->first(d).put("linkUrl","https://example.com"),d->first(d).put("linkUrl","/pages/agreement/index?kind=privacy&extra=1"),
            d->first(d).put("linkUrl","/pages/%61greement/index?kind=privacy"),d->first(d).put("linkUrl","///pages/agreement/index?kind=privacy"),
            d->first(d).put("linkUrl","/pages/agreement/index?kind=%70rivacy"),d->first(d).put("linkUrl","/pages/profile/index?kind=privacy"),
            d->first(d).putArray("actions").add("admin"),d->first(d).putArray("actions").add("contact").add("contact"),d->first(d).putNull("enabled"),
            d->{for(int n=0;n<8;n++){ObjectNode section=first(d).deepCopy();section.put("id","large-"+n);section.put("body","字".repeat(8000));d.withArray("sections").add(section);}},
            d->{while(d.withArray("sections").size()<31){ObjectNode section=first(d).deepCopy();section.put("id","extra-"+d.withArray("sections").size());d.withArray("sections").add(section);}}
        );
        for(var mutate:invalid){ObjectNode draft=document(initial);mutate.accept(draft);send(post("/api/admin/documents/privacy"),Map.of("revision",1,"document",draft),token,400);}
        send(post("/api/admin/documents/privacy"),Map.of("document",document(initial)),token,400);send(post("/api/admin/documents/privacy/publish"),Map.of("revision",0),token,400);
        assertThat(state("privacy").path("revision").asLong()).isEqualTo(1);assertThat(published("privacy")).isEqualTo(publicBefore);
    }
    @Test void mediaPlainTextAndDisabledDraftsHaveHonestPublishBehavior()throws Exception {
        JsonNode initial=state("privacy");ObjectNode draft=document(initial);
        for(JsonNode section:draft.path("sections"))((ObjectNode)section).put("enabled",false);
        send(post("/api/admin/documents/privacy"),Map.of("revision",1,"document",draft),token,200);
        send(post("/api/admin/documents/privacy/publish"),Map.of("revision",2),token,400);
        first(draft).put("enabled",true);first(draft).put("body","<script>alert('plain text')</script> {{servicePhone}}");first(draft).put("mediaType","VIDEO");first(draft).put("mediaUrl","https://media.example.com/intro.mp4");first(draft).put("posterUrl","https://media.example.com/poster.jpg");first(draft).put("linkUrl","/pages/agreement/index?kind=service");
        send(post("/api/admin/documents/privacy"),Map.of("revision",2,"document",draft),token,200);
        send(post("/api/admin/documents/privacy/publish"),Map.of("revision",3),token,200);
        JsonNode content=published("privacy").path("document").path("sections");assertThat(content.size()).isEqualTo(1);assertThat(content.get(0).path("body").asText()).startsWith("<script>alert('plain text')</script>");
        assertThat(content.get(0).path("posterUrl").asText()).isEqualTo("https://media.example.com/poster.jpg");assertThat(content.get(0).path("linkUrl").asText()).isEqualTo("/pages/agreement/index?kind=service");
    }
}
