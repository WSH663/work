package cn.accessible.travel;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.profiles.active=production","spring.datasource.url=jdbc:h2:mem:production;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","app.admin-password=ProductionTest123!","app.dev-payments-enabled=true","app.wechat-app-id=","app.wechat-app-secret="})
@AutoConfigureMockMvc
class ProductionGuardTest {
    @Autowired MockMvc mvc;@Autowired Db db;@Autowired Auth auth;@Autowired ObjectMapper json;
    @Test void productionRejectsDevLoginPhoneBindingAndSimulatedPayments() throws Exception {
        mvc.perform(post("/api/auth/dev-login").contentType("application/json").content("{}")).andExpect(status().isNotFound());
        long uid=db.insert("INSERT INTO users(nickname,invite_code) VALUES (?,?)","生产测试","PROD-TEST-INVITE");String token=auth.loginResult(db.one("SELECT * FROM users WHERE id=?",uid)).get("token").toString();
        mvc.perform(patch("/api/me").header("Authorization","Bearer "+token).contentType("application/json").content("{\"phone\":\"13800138000\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders/1/pay").header("Authorization","Bearer "+token).contentType("application/json").content("{}")).andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/auth/wechat/login").contentType("application/json").content("{\"code\":\"test\",\"agreementAccepted\":true}")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/public/config")).andExpect(status().isOk()).andExpect(jsonPath("$.data.devLoginEnabled").value(false)).andExpect(jsonPath("$.data.amapWebKey").doesNotExist());
    }
}
