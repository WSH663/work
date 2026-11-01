package cn.accessible.travel;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MapServiceTest {
    final ObjectMapper json=new ObjectMapper();
    @SuppressWarnings("unchecked") HttpResponse<byte[]> reply(String type,byte[] body){var response=(HttpResponse<byte[]>)mock(HttpResponse.class);when(response.statusCode()).thenReturn(200);when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type",List.of(type)),(a,b)->true));when(response.body()).thenReturn(body);return response;}
    MapService service(HttpClient http,String body) throws Exception {doReturn(reply("application/json",body.getBytes(StandardCharsets.UTF_8))).when(http).send(any(),any());return new MapService("server-only-test-key",json,http);}
    @Test void reverseUsesProviderAddressInsteadOfInventingCurrentLocation() throws Exception {
        var http=mock(HttpClient.class);var maps=service(http,"""
            {"status":"1","regeocode":{"formatted_address":"示例省示例市示例区示例路66号","addressComponent":{"province":"示例省","city":"示例市","district":"示例区","building":{"name":[]},"neighborhood":{"name":[]},"streetNumber":{"street":"示例路","number":"66号"}}}}
            """);
        var result=maps.reverse("120.123123456,30.012");assertThat(result).containsEntry("name","示例路66号").containsEntry("address","示例省示例市示例区示例路66号").containsEntry("district","示例省示例市示例区").containsEntry("location","120.123123,30.012000");
        var request=ArgumentCaptor.forClass(HttpRequest.class);verify(http).send(request.capture(),any());assertThat(request.getValue().uri().getPath()).isEqualTo("/v3/geocode/regeo");assertThat(result.toString()).doesNotContain("server-only-test-key");
        var empty=service(mock(HttpClient.class),"{\"status\":\"1\",\"regeocode\":{\"formatted_address\":[]}}");assertThatThrownBy(()->empty.reverse("120.123,30.012")).isInstanceOfSatisfying(Api.Failure.class,e->assertThat(e.status).isEqualTo(404));
    }
    @Test void nearbyReturnsRealPoiDistanceAndDiscardsUnusableProviderRows() throws Exception {
        var maps=service(mock(HttpClient.class),"""
            {"status":"1","pois":[{"id":"B001","name":"示例车站","address":"示例大道","location":"120.123,30.012","pname":"示例省","cityname":"示例市","adname":"示例区","distance":"86.5"},{"id":"bad","name":"无坐标POI","location":[],"distance":"1"},{"id":"bad2","name":"虚假距离POI","location":"120.123,30.012","distance":[]}]}
            """);
        var places=maps.nearby("120.123,30.012");assertThat(places).hasSize(1);assertThat(places.get(0)).containsEntry("name","示例车站").containsEntry("district","示例省示例市示例区");assertThat(places.get(0).get("distance").toString()).isEqualTo("86.5");
    }
    @Test void staticMapReturnsValidatedPngAndCachesNormalizedCoordinates() throws Exception {
        var http=mock(HttpClient.class);var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(128,180,BufferedImage.TYPE_INT_RGB),"png",bytes);byte[] png=bytes.toByteArray();doReturn(reply("image/png",png)).when(http).send(any(),any());
        var maps=new MapService("server-only-test-key",json,http);assertThat(maps.staticMap("120.123,30.012",15,128,180)).isEqualTo(png);assertThat(maps.staticMap("120.123000,30.012000",15,128,180)).isEqualTo(png);verify(http,times(1)).send(any(),any());
        assertThatThrownBy(()->maps.staticMap("120.123,30.012",15,129,180)).isInstanceOfSatisfying(Api.Failure.class,e->assertThat(e.status).isEqualTo(502));
    }
    @Test void staticProviderJsonFailureAndMalformedImagesNeverBecomeFakeMapImages() throws Exception {
        var maps=service(mock(HttpClient.class),"{\"status\":\"0\",\"infocode\":\"10001\",\"info\":\"INVALID_USER_KEY\"}");
        assertThatThrownBy(()->maps.staticMap("120.123,30.012",15,128,128)).isInstanceOfSatisfying(Api.Failure.class,e->{assertThat(e.status).isEqualTo(502);assertThat(e.getMessage()).contains("10001").doesNotContain("server-only-test-key");});
        var badHttp=mock(HttpClient.class);doReturn(reply("image/png","not-a-png".getBytes(StandardCharsets.UTF_8))).when(badHttp).send(any(),any());assertThatThrownBy(()->new MapService("test",json,badHttp).staticMap("120.123,30.012",15,128,128)).isInstanceOf(Api.Failure.class);
        var timeout=mock(HttpClient.class);doThrow(new HttpTimeoutException("timeout")).when(timeout).send(any(),any());assertThatThrownBy(()->new MapService("test",json,timeout).reverse("120.123,30.012")).isInstanceOfSatisfying(Api.Failure.class,e->assertThat(e.status).isEqualTo(502));
    }
    @Test void validatesParametersBeforeCallingProviderAndRequiresConfiguredKey() throws Exception {
        var http=mock(HttpClient.class);var maps=new MapService("test",json,http);
        for(String value:List.of("NaN,38","114.48,Infinity","0,0","114.4","181,39","114,90"))assertThatThrownBy(()->maps.reverse(value)).isInstanceOf(Api.Failure.class);
        for(int zoom:List.of(0,18))assertThatThrownBy(()->maps.staticMap("120.123,30.012",zoom,750,360)).isInstanceOf(Api.Failure.class);
        for(int width:List.of(127,1025))assertThatThrownBy(()->maps.staticMap("120.123,30.012",15,width,360)).isInstanceOf(Api.Failure.class);verifyNoInteractions(http);
        assertThatThrownBy(()->new MapService("",json,http).nearby("120.123,30.012")).isInstanceOfSatisfying(Api.Failure.class,e->assertThat(e.status).isEqualTo(503));
    }
    @Test void endpointsHavePrecisePublicWhitelistAndApplyRateLimit() throws Exception {
        var auth=new Auth(mock(Db.class),mock(Environment.class),"admin","password");
        for(String path:List.of("/api/map/reverse","/api/map/nearby","/api/map/static")){var request=new MockHttpServletRequest("GET",path);assertThat(auth.preHandle(request,new MockHttpServletResponse(),new Object())).isTrue();}
        assertThatThrownBy(()->auth.preHandle(new MockHttpServletRequest("GET","/api/map/secrets"),new MockHttpServletResponse(),new Object())).isInstanceOfSatisfying(Api.Failure.class,e->assertThat(e.status).isEqualTo(401));
        var maps=mock(MapService.class);when(maps.reverse(any())).thenReturn(Map.of("name","地点"));var controller=new MapController(maps);var request=new MockHttpServletRequest();request.setRemoteAddr("192.0.2.5");for(int i=0;i<90;i++)controller.reverse("120.123,30.012",request);
        assertThatThrownBy(()->controller.reverse("120.123,30.012",request)).isInstanceOfSatisfying(Api.Failure.class,e->assertThat(e.status).isEqualTo(429));verify(maps,times(90)).reverse(any());
        var mvc=MockMvcBuilders.standaloneSetup(new MapController(maps)).setControllerAdvice(new ApiErrors()).build();mvc.perform(get("/api/map/static").param("location","120.123,30.012").param("zoom","invalid")).andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/json"));
        byte[] png={(byte)137,80,78,71};when(maps.staticMap("120.123,30.012",15,750,360)).thenReturn(png);mvc.perform(get("/api/map/static").param("location","120.123,30.012")).andExpect(status().isOk()).andExpect(content().contentType("image/png")).andExpect(content().bytes(png)).andExpect(header().string("X-Content-Type-Options","nosniff"));
    }
}
