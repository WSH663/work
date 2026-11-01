package cn.accessible.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import javax.imageio.ImageIO;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MapService {
    final String key;final ObjectMapper json;final HttpClient http;
    private static final int MAX_RESPONSE_BYTES=4*1024*1024;
    private final LinkedHashMap<String,CachedImage> images=new LinkedHashMap<>(32,0.75f,true);
    private int cachedBytes;
    @Autowired MapService(@Value("${app.amap-web-key:}")String key,ObjectMapper json){this(key,json,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build());}
    MapService(String key,ObjectMapper json,HttpClient http){this.key=key;this.json=json;this.http=http;}
    public List<Map<String,Object>> tips(String keywords,String city){
        Api.require(keywords!=null&&!keywords.isBlank()&&keywords.length()<=100,"请输入100字以内的地点关键词");
        var data=get("/v3/assistant/inputtips",Map.of("keywords",keywords,"city",city==null?"":city.trim(),"citylimit","false","datatype","poi"));
        var result=new ArrayList<Map<String,Object>>();
        for(var tip:data.path("tips")){String location=tip.path("location").asText("");if(location.isBlank())continue;result.add(Map.of("id",tip.path("id").asText(),"name",tip.path("name").asText(),"address",tip.path("address").asText(),"location",location,"district",tip.path("district").asText()));}return result;
    }
    public Map<String,Object> reverse(String location){
        location=canonicalLocation(location);var result=get("/v3/geocode/regeo",Map.of("location",location,"extensions","base","output","JSON"));var address=result.path("regeocode");
        String formatted=text(address,"formatted_address");if(formatted.isBlank())throw Api.fail(404,"该位置暂无可用地址，请在地图上重新选择地点");
        var component=address.path("addressComponent");String street=text(component.path("streetNumber"),"street")+text(component.path("streetNumber"),"number");
        String name=text(component.path("building"),"name");if(name.isBlank())name=text(component.path("neighborhood"),"name");if(name.isBlank())name=street;if(name.isBlank())name=formatted;
        return Map.of("name",name,"address",formatted,"location",location,"district",district(component));
    }
    public List<Map<String,Object>> nearby(String location){
        location=canonicalLocation(location);var result=get("/v3/place/around",Map.of("location",location,"radius","1500","offset","20","page","1","sortrule","distance","extensions","base","output","JSON","types","050000|060000|070000|090000|120000|141200|150000"));
        var places=new ArrayList<Map<String,Object>>();for(var poi:result.path("pois")){
            String coordinates=text(poi,"location"),name=text(poi,"name");if(coordinates.isBlank()||name.isBlank())continue;
            try{coordinates=canonicalLocation(coordinates);BigDecimal distance=new BigDecimal(text(poi,"distance"));if(distance.signum()<0)continue;
                places.add(Map.of("id",text(poi,"id"),"name",name,"address",text(poi,"address"),"location",coordinates,"district",text(poi,"pname")+text(poi,"cityname")+text(poi,"adname"),"distance",distance));
            }catch(IllegalArgumentException|Api.Failure ignored){/* Discard unusable provider POIs instead of inventing coordinates or distances. */}
        }return places;
    }
    public synchronized byte[] staticMap(String location,int zoom,int width,int height){
        location=canonicalLocation(location);Api.require(zoom>=1&&zoom<=17,"地图缩放级别须在1至17之间");Api.require(width>=128&&width<=1024&&height>=128&&height<=1024,"地图尺寸须在128至1024像素之间");
        String cacheKey=location+":"+zoom+":"+width+":"+height;long now=System.currentTimeMillis();
        var stale=images.entrySet().iterator();while(stale.hasNext()){var item=stale.next();if(item.getValue().expiresAt<now){cachedBytes-=item.getValue().bytes.length;stale.remove();}}
        var cached=images.get(cacheKey);if(cached!=null)return cached.bytes;
        var response=exchange("/v3/staticmap",Map.of("location",location,"zoom",Integer.toString(zoom),"size",width+"*"+height,"scale","1"));
        byte[] bytes=response.body;
        if(!response.contentType.toLowerCase(Locale.ROOT).startsWith("image/png")||bytes.length<24||bytes[0]!=(byte)137||bytes[1]!=80||bytes[2]!=78||bytes[3]!=71){
            // Providers return JSON errors with HTTP 200 for invalid keys and quota errors.
            parseJson(bytes);throw Api.fail(502,"地图服务未返回有效PNG图片，请稍后重试");
        }
        try(var stream=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
            var readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())throw Api.fail(502,"地图图片数据无法解析");var reader=readers.next();
            try{reader.setInput(stream);if(reader.getWidth(0)!=width||reader.getHeight(0)!=height)throw Api.fail(502,"地图服务返回的图片尺寸不正确");}finally{reader.dispose();}
        }catch(IOException exception){throw Api.fail(502,"地图图片数据无法解析");}
        while(!images.isEmpty()&&(images.size()>=32||cachedBytes+bytes.length>16*1024*1024)){var oldest=images.entrySet().iterator();cachedBytes-=oldest.next().getValue().bytes.length;oldest.remove();}
        images.put(cacheKey,new CachedImage(bytes,now+120_000));cachedBytes+=bytes.length;return bytes;
    }
    public Route route(String origin,String destination){Inputs.location(origin);Inputs.location(destination);Api.require(!origin.equals(destination),"起终点不能相同");
        JsonNode result=get("/v3/direction/driving",Map.of("origin",origin,"destination",destination,"extensions","base","strategy","0"));
        JsonNode path=result.path("route").path("paths").path(0);Api.require(!path.isMissingNode(),"未查询到可通行路线，请更换地点或联系客服");
        BigDecimal distance=new BigDecimal(path.path("distance").asText("0")).divide(new BigDecimal("1000"),2,RoundingMode.HALF_UP);
        Api.require(distance.signum()>0,"未查询到有效驾车里程");int minutes=(int)Math.ceil(path.path("duration").asDouble()/60);return new Route(distance,minutes,"AMAP");
    }
    private JsonNode get(String path,Map<String,String> parameters){return parseJson(exchange(path,parameters).body);}
    private ProviderResponse exchange(String path,Map<String,String> parameters){
        if(key.isBlank())throw Api.fail(503,"高德地图Web服务密钥尚未配置，请联系管理员");
        StringJoiner query=new StringJoiner("&");query.add("key="+encode(key));parameters.forEach((k,v)->query.add(k+"="+encode(v)));
        try{var request=HttpRequest.newBuilder(URI.create("https://restapi.amap.com"+path+"?"+query)).timeout(Duration.ofSeconds(10)).GET().build();
            var response=http.send(request,info->new LimitedBodySubscriber(MAX_RESPONSE_BYTES));
            if(response.statusCode()!=200)throw Api.fail(502,"地图服务暂时不可用");return new ProviderResponse(response.headers().firstValue("Content-Type").orElse(""),response.body());
        }catch(Api.Failure e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw Api.fail(503,"地图查询已中断");}catch(Exception e){throw Api.fail(502,"地图服务连接失败，请稍后重试");}
    }
    private JsonNode parseJson(byte[] bytes){
        try{var result=json.readTree(bytes);if(result==null||!"1".equals(result.path("status").asText())){
            String code=result==null?"":text(result,"infocode");if(!code.matches("[0-9]{1,8}"))code="UNKNOWN";
            throw Api.fail(502,"地图服务请求失败，请检查密钥权限或额度（"+code+"）");}return result;
        }catch(IOException exception){throw Api.fail(502,"地图服务返回了无效数据，请稍后重试");}
    }
    static String canonicalLocation(String value){if(value==null||value.length()>80)throw Api.fail(400,"经纬度格式不正确");Inputs.location(value);var parts=value.split(",");try{return new BigDecimal(parts[0].trim()).setScale(6,RoundingMode.HALF_UP).toPlainString()+","+new BigDecimal(parts[1].trim()).setScale(6,RoundingMode.HALF_UP).toPlainString();}catch(NumberFormatException exception){throw Api.fail(400,"经纬度格式不正确");}}
    private static String text(JsonNode node,String key){var value=node.path(key);return value.isTextual()?value.asText():"";}
    private static String district(JsonNode component){String province=text(component,"province"),city=text(component,"city");return province+(province.equals(city)?"":city)+text(component,"district");}
    private record ProviderResponse(String contentType,byte[] body){}
    private record CachedImage(byte[] bytes,long expiresAt){}
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();final int maximum;long size;Flow.Subscription subscription;
        LimitedBodySubscriber(int maximum){this.maximum=maximum;}
        public CompletionStage<byte[]> getBody(){return delegate.getBody();}
        public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;delegate.onSubscribe(subscription);}
        public void onNext(List<ByteBuffer> buffers){for(var buffer:buffers)size+=buffer.remaining();if(size>maximum){subscription.cancel();delegate.onError(new IOException("Provider response exceeds configured limit"));}else delegate.onNext(buffers);}
        public void onError(Throwable throwable){delegate.onError(throwable);}
        public void onComplete(){delegate.onComplete();}
    }
    private static String encode(String text){return URLEncoder.encode(text,StandardCharsets.UTF_8);}
    public record Route(BigDecimal distanceKm,int durationMinutes,String source) {}
}
