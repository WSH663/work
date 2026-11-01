package cn.accessible.travel;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/map")
public class MapController {
    final MapService maps;
    private final LinkedHashMap<String,Window> clients=new LinkedHashMap<>(128,0.75f,true);
    MapController(MapService maps){this.maps=maps;}
    @GetMapping("/reverse") public Api.Result reverse(@RequestParam String location,HttpServletRequest request){limit(request);return Api.ok(maps.reverse(location));}
    @GetMapping("/nearby") public Api.Result nearby(@RequestParam String location,HttpServletRequest request){limit(request);return Api.ok(maps.nearby(location));}
    @GetMapping("/static") public ResponseEntity<byte[]> image(@RequestParam String location,@RequestParam(defaultValue="15")int zoom,@RequestParam(defaultValue="750")int width,@RequestParam(defaultValue="360")int height,HttpServletRequest request){
        limit(request);byte[] png=maps.staticMap(location,zoom,width,height);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.maxAge(120,TimeUnit.SECONDS).cachePrivate()).header("X-Content-Type-Options","nosniff").body(png);
    }
    private synchronized void limit(HttpServletRequest request){
        // Use the socket peer; accepting caller-supplied forwarding headers would permit quota bypass.
        String ip=request.getRemoteAddr();long now=System.currentTimeMillis();Window window=clients.get(ip);
        if(window==null||window.startedAt+60_000<=now){window=new Window(now,0);clients.put(ip,window);}
        if(++window.requests>90)throw Api.fail(429,"地图请求过于频繁，请稍后重试");
        while(clients.size()>4096){var oldest=clients.entrySet().iterator();oldest.next();oldest.remove();}
    }
    private static class Window{final long startedAt;int requests;Window(long startedAt,int requests){this.startedAt=startedAt;this.requests=requests;}}
}
