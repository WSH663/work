package cn.accessible.travel;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettingsService {
    final Db db; final ObjectMapper json;
    static final Set<String> KEYS=Set.of("platformName","servicePhone","notice","serviceCity","serviceArea","advanceMinutes","maxAdvanceDays","commissionRate","withdrawalMinimum","cancelHours");
    SettingsService(Db db,ObjectMapper json){this.db=db;this.json=json;}
    public Map<String,Object> all(){var result=new LinkedHashMap<String,Object>();for(var row:db.list("SELECT * FROM settings")){try{result.put(row.get("settingKey").toString(),json.readValue(row.get("settingValue").toString(),Object.class));}catch(Exception e){throw new IllegalStateException(e);}}return result;}
    public BigDecimal number(String key){return new BigDecimal(all().get(key).toString());}
    @Transactional public Map<String,Object> save(Map<String,Object> body){
        for(String key:body.keySet())Api.require(KEYS.contains(key),"不支持的配置项："+key);
        var merged=new LinkedHashMap<>(all());merged.putAll(body);
        for(String key:List.of("platformName","servicePhone","notice","serviceCity","serviceArea"))Inputs.required(merged,key,key.equals("notice")?1500:200);
        int advance=Inputs.integer(merged,"advanceMinutes",60,0,10080),maxDays=Inputs.integer(merged,"maxAdvanceDays",30,1,365);Api.require(advance<=maxDays*1440,"至少提前预约不能超过最多提前预约天数");
        BigDecimal rate=Inputs.decimal(merged,"commissionRate",null);Api.require(rate!=null&&rate.signum()>=0&&rate.compareTo(new BigDecimal("0.5"))<=0,"佣金比例须在0至0.5之间");
        Api.require(rate.scale()<=4,"佣金比例最多保留4位小数，请调整后保存");
        var minimum=Inputs.decimal(merged,"withdrawalMinimum",BigDecimal.ZERO);Api.require(minimum.compareTo(BigDecimal.ONE)>=0&&minimum.compareTo(new BigDecimal("999999.99"))<=0&&minimum.scale()<=2,"最低提现金额须在1至999999.99元之间且最多两位小数");
        var cancelHours=Inputs.decimal(merged,"cancelHours",BigDecimal.ZERO);Api.require(cancelHours.signum()>=0&&cancelHours.compareTo(new BigDecimal("720"))<=0&&cancelHours.scale()<=2,"取消时限须在0至720小时之间且最多两位小数");
        for(var entry:body.entrySet()){try{db.update("UPDATE settings SET setting_value=? WHERE setting_key=?",json.writeValueAsString(entry.getValue()),entry.getKey());}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw Api.fail(400,"配置格式错误");}}return all();
    }
}
