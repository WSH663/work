package cn.accessible.travel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlatformService {
    final Db db;final SettingsService settings;final MapService maps;final SubscriptionNotificationAdapter notifications;
    PlatformService(Db db,SettingsService settings,MapService maps,SubscriptionNotificationAdapter notifications){this.db=db;this.settings=settings;this.maps=maps;this.notifications=notifications;}
    public Map<String,Object> vehicle(long id){var row=db.one("SELECT * FROM vehicles WHERE id=?",id);Api.require(Boolean.TRUE.equals(row.get("enabled")),"该车型已停用");return row;}
    public BigDecimal fare(Map<String,Object> vehicle,BigDecimal distance){return Inputs.number(vehicle,"baseFare").add(distance.subtract(Inputs.number(vehicle,"includedKm")).max(BigDecimal.ZERO).multiply(Inputs.number(vehicle,"perKm"))).add(Inputs.number(vehicle,"surcharge")).setScale(2,RoundingMode.HALF_UP);}
    public Map<String,Object> estimate(Map<String,Object> body){long vehicleId=Inputs.id(body,"vehicleId");var car=vehicle(vehicleId);var route=maps.route(Inputs.required(body,"origin",60),Inputs.required(body,"destination",60));return Map.of("vehicleId",vehicleId,"distanceKm",route.distanceKm(),"durationMinutes",route.durationMinutes(),"amount",fare(car,route.distanceKm()),"source",route.source());}
    public void audit(long actor,String action,String target){var user=db.one("SELECT nickname FROM users WHERE id=?",actor);db.insert("INSERT INTO audit_logs(actor_id,actor_name,action,target) VALUES (?,?,?,?)",actor,user.get("nickname"),action,target);}
    public void message(long userId,String title,String content){message(userId,title,content,null);}
    public void message(long userId,String title,String content,Long orderId){db.insert("INSERT INTO messages(user_id,title,content,order_id) VALUES (?,?,?,?)",userId,title,content,orderId);notifications.onMessageCreated(userId,title,content);}
    @Transactional public Map<String,Object> saveVehicle(Long id,Map<String,Object> body){
        var values=id==null?new LinkedHashMap<String,Object>():new LinkedHashMap<>(db.one("SELECT * FROM vehicles WHERE id=?",id));values.putAll(body);
        String name=Inputs.required(values,"name",80),code=Inputs.required(values,"code",20),description=Inputs.required(values,"description",1000);int seats=Inputs.integer(values,"seats",4,1,30);
        var money=new ArrayList<BigDecimal>();for(String field:List.of("baseFare","perKm","includedKm","charterHourly","minimumHours","surcharge")){var n=Inputs.decimal(values,field,BigDecimal.ZERO);Api.require(n.signum()>=0&&n.compareTo(new BigDecimal("100000"))<=0&&n.scale()<=2,"车型价格或里程不正确");money.add(n);}
        Api.require(money.get(0).signum()>0&&money.get(3).signum()>0&&money.get(4).signum()>0,"起步价、包车时价、最低时长须大于0");
        Api.require(money.get(4).compareTo(new BigDecimal("240"))<=0,"包车最低时长不能超过240小时");
        boolean enabled=Inputs.bool(values,"enabled",true);var args=new ArrayList<Object>(List.of(name,code,seats,description));args.addAll(money);args.add(enabled);
        if(id==null)id=db.insert("INSERT INTO vehicles(name,code,seats,description,base_fare,per_km,included_km,charter_hourly,minimum_hours,surcharge,enabled) VALUES (?,?,?,?,?,?,?,?,?,?,?)",args.toArray());
        else{args.add(id);db.update("UPDATE vehicles SET name=?,code=?,seats=?,description=?,base_fare=?,per_km=?,included_km=?,charter_hourly=?,minimum_hours=?,surcharge=?,enabled=? WHERE id=?",args.toArray());}return db.one("SELECT * FROM vehicles WHERE id=?",id);
    }
    @Transactional public Map<String,Object> saveContent(Long id,Map<String,Object> body){
        var values=id==null?new LinkedHashMap<String,Object>():new LinkedHashMap<>(db.one("SELECT * FROM content WHERE id=?",id));values.putAll(body);
        String kind=Inputs.required(values,"kind",20),mediaType=Inputs.str(values,"mediaType",20);if(mediaType.isBlank())mediaType="TEXT";
        Api.require(Set.of("BANNER","AD","NOTICE","SERVICE").contains(kind),"内容类型不支持");Api.require(Set.of("TEXT","IMAGE","VIDEO").contains(mediaType),"媒体类型不支持");
        String title=Inputs.required(values,"title",120),text=Inputs.str(values,"body",3000),media=Inputs.str(values,"mediaUrl",1000),link=Inputs.str(values,"linkUrl",1000);
        String poster=Inputs.str(values,"posterUrl",1000),action=Inputs.str(values,"actionText",12);
        Api.require("TEXT".equals(mediaType)||!media.isEmpty(),"图片或视频内容必须填写媒体地址");
        if(!media.isEmpty())validateMediaUrl(media);
        if("VIDEO".equals(mediaType)){if(!poster.isEmpty())validateMediaUrl(poster);}else poster="";
        validateContentLink(link);
        if(link.isEmpty())action="";
        int sort=Inputs.integer(values,"sortOrder",0,0,9999),skip=Inputs.integer(values,"skipSeconds",3,0,15);boolean enabled=Inputs.bool(values,"enabled",true);
        if(id==null)id=db.insert("INSERT INTO content(kind,title,body,media_url,media_type,link_url,poster_url,action_text,sort_order,enabled,skip_seconds) VALUES (?,?,?,?,?,?,?,?,?,?,?)",kind,title,text,media,mediaType,link,poster,action,sort,enabled,skip);
        else db.update("UPDATE content SET kind=?,title=?,body=?,media_url=?,media_type=?,link_url=?,poster_url=?,action_text=?,sort_order=?,enabled=?,skip_seconds=? WHERE id=?",kind,title,text,media,mediaType,link,poster,action,sort,enabled,skip,id);
        return db.one("SELECT * FROM content WHERE id=?",id);
    }
    private void validateMediaUrl(String value){
        try {var uri=URI.create(value);Api.require("https".equalsIgnoreCase(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null&&uri.getFragment()==null,"媒体地址须为有效HTTPS图片或视频链接");}
        catch(IllegalArgumentException exception){throw Api.fail(400,"媒体地址须为有效HTTPS图片或视频链接");}
    }
    private void validateContentLink(String value){
        if(value.isEmpty())return;
        try {
            var uri=URI.create(value);String path=uri.getPath();
            Api.require(uri.getScheme()==null&&uri.getHost()==null&&uri.getFragment()==null&&Set.of("/pages/index/index","/pages/booking/index","/pages/orders/index","/pages/profile/index","/pages/promotion/index","/pages/messages/index","/pages/agreement/index").contains(path),"跳转仅支持已开放的小程序页面，暂不支持外部网页");
            if(uri.getRawQuery()!=null){
                var parts=uri.getRawQuery().split("=",-1);Api.require(parts.length==2,"跳转页面参数不正确");
                String key=URLDecoder.decode(parts[0],StandardCharsets.UTF_8),valuePart=URLDecoder.decode(parts[1],StandardCharsets.UTF_8);
                boolean booking=path.equals("/pages/booking/index")&&key.equals("type")&&Set.of("RIDE","CHARTER").contains(valuePart);
                boolean agreement=path.equals("/pages/agreement/index")&&key.equals("kind")&&Set.of("service","notice","terms","privacy").contains(valuePart);
                Api.require(booking||agreement,"跳转页面参数不正确");
            }
        }catch(IllegalArgumentException exception){throw Api.fail(400,"跳转地址格式不正确");}
    }
}
