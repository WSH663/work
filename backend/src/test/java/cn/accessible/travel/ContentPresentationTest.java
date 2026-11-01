package cn.accessible.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"spring.profiles.active=dev","spring.datasource.url=jdbc:h2:mem:content-presentation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","app.admin-username=content-test-admin","app.admin-password=ContentTest123!"})
@AutoConfigureMockMvc
class ContentPresentationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Db db;
    String token;

    @BeforeEach void login() throws Exception {
        token=send(post("/api/auth/admin/login"),Map.of("username","content-test-admin","password","ContentTest123!"),false,200).path("data").path("token").asText();
    }

    JsonNode send(MockHttpServletRequestBuilder request,Object body,boolean authenticated,int expected) throws Exception {
        if(body!=null)request.contentType("application/json").content(json.writeValueAsString(body));
        if(authenticated)request.header("Authorization","Bearer "+token);
        String response=mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    Map<String,Object> video() {
        var body=new LinkedHashMap<String,Object>();
        body.put("kind","AD");body.put("title","预约服务介绍");body.put("mediaType","VIDEO");
        body.put("mediaUrl","https://media.example.com/travel.mp4");
        body.put("posterUrl","https://media.example.com/travel-cover.jpg");
        body.put("linkUrl","/pages/booking/index?type=RIDE");body.put("actionText","立即预约");
        return body;
    }

    @Test void migrationPreservesExistingContentAndAddsEmptyPresentationFields() {
        var original=db.one("SELECT * FROM content WHERE title=?","让出行更从容");
        assertThat(original).containsEntry("posterUrl","").containsEntry("actionText","").containsEntry("mediaType","TEXT");
        assertThat(db.count("SELECT COUNT(*) FROM flyway_schema_history WHERE version='8' AND success=TRUE")).isEqualTo(1);
    }

    @Test void videoPresentationIsSavedAndPublishedWithoutChangingDisplayRules() throws Exception {
        var body=video();body.put("skipSeconds",7);
        JsonNode saved=send(post("/api/admin/content"),body,true,200).path("data");
        assertThat(saved.path("posterUrl").asText()).isEqualTo(body.get("posterUrl"));
        assertThat(saved.path("actionText").asText()).isEqualTo("立即预约");
        assertThat(saved.path("skipSeconds").asInt()).isEqualTo(7);
        JsonNode ads=send(get("/api/public/home"),null,false,200).path("data").path("ads");
        JsonNode published=null;
        for(JsonNode ad:ads)if(ad.path("id").asLong()==saved.path("id").asLong())published=ad;
        assertThat(published).isNotNull();
        assertThat(published.path("posterUrl").asText()).isEqualTo(body.get("posterUrl"));
        assertThat(published.path("actionText").asText()).isEqualTo("立即预约");
    }

    @Test void oldClientsCanCreateAndPatchWithoutDroppingExistingPresentation() throws Exception {
        JsonNode legacy=send(post("/api/admin/content"),Map.of("kind","BANNER","title","旧版首页内容"),true,200).path("data");
        assertThat(legacy.path("posterUrl").asText()).isEmpty();
        assertThat(legacy.path("actionText").asText()).isEmpty();
        JsonNode saved=send(post("/api/admin/content"),video(),true,200).path("data");
        JsonNode updated=send(patch("/api/admin/content/"+saved.path("id").asLong()),Map.of("title","更新活动标题"),true,200).path("data");
        assertThat(updated.path("title").asText()).isEqualTo("更新活动标题");
        assertThat(updated.path("posterUrl").asText()).isEqualTo(saved.path("posterUrl").asText());
        assertThat(updated.path("actionText").asText()).isEqualTo("立即预约");
    }

    @Test void changingMediaOrRemovingDestinationClearsUnusedPresentation() throws Exception {
        JsonNode saved=send(post("/api/admin/content"),video(),true,200).path("data");long id=saved.path("id").asLong();
        JsonNode image=send(patch("/api/admin/content/"+id),Map.of("mediaType","IMAGE","mediaUrl","https://media.example.com/image.jpg"),true,200).path("data");
        assertThat(image.path("posterUrl").asText()).isEmpty();
        assertThat(image.path("actionText").asText()).isEqualTo("立即预约");
        JsonNode noLink=send(patch("/api/admin/content/"+id),Map.of("linkUrl",""),true,200).path("data");
        assertThat(noLink.path("actionText").asText()).isEmpty();
        var text=new LinkedHashMap<String,Object>(Map.of("kind","AD","title","文字通知","posterUrl","https://media.example.com/unused.jpg","actionText","无跳转"));
        JsonNode normalized=send(post("/api/admin/content"),text,true,200).path("data");
        assertThat(normalized.path("posterUrl").asText()).isEmpty();
        assertThat(normalized.path("actionText").asText()).isEmpty();
    }

    @Test void videoPosterRequiresHttpsAndFieldsEnforceTheirStorageLimits() throws Exception {
        for(String poster:List.of("http://media.example.com/cover.jpg","https://","https://user:password@media.example.com/cover.jpg","https://media.example.com/cover.jpg#fragment","javascript:alert(1)")){
            var body=video();body.put("posterUrl",poster);send(post("/api/admin/content"),body,true,400);
        }
        var longPoster=video();longPoster.put("posterUrl","https://media.example.com/"+"x".repeat(1000));
        assertThat(send(post("/api/admin/content"),longPoster,true,400).path("message").asText()).contains("posterUrl");
        var longAction=video();longAction.put("actionText","字".repeat(13));
        assertThat(send(post("/api/admin/content"),longAction,true,400).path("message").asText()).contains("actionText");
        String prefix="https://media.example.com/";
        var limits=video();limits.put("posterUrl",prefix+"x".repeat(1000-prefix.length()));limits.put("actionText","字".repeat(12));
        JsonNode saved=send(post("/api/admin/content"),limits,true,200).path("data");
        assertThat(saved.path("posterUrl").asText()).hasSize(1000);assertThat(saved.path("actionText").asText()).hasSize(12);
        var noPoster=video();noPoster.put("posterUrl","");
        assertThat(send(post("/api/admin/content"),noPoster,true,200).path("data").path("posterUrl").asText()).isEmpty();
    }
}
