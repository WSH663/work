package cn.accessible.travel;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class DocumentsController {
    final DocumentsService documents;
    DocumentsController(DocumentsService documents){this.documents=documents;}
    @GetMapping("/public/documents/{kind}") public Api.Result published(@PathVariable String kind){return Api.ok(documents.published(kind));}
    @GetMapping("/admin/documents/{kind}") public Api.Result state(@PathVariable String kind,HttpServletRequest request){Auth.roles(request,"ADMIN","OPERATOR");return Api.ok(documents.get(kind));}
    @PostMapping("/admin/documents/{kind}") public Api.Result save(@PathVariable String kind,@RequestBody DocumentsService.SaveRequest body,HttpServletRequest request){Auth.roles(request,"ADMIN","OPERATOR");return Api.ok(documents.save(kind,body,Auth.userId(request)));}
    @PostMapping("/admin/documents/{kind}/publish") public Api.Result publish(@PathVariable String kind,@RequestBody DocumentsService.PublishRequest body,HttpServletRequest request){Auth.roles(request,"ADMIN","OPERATOR");return Api.ok(documents.publish(kind,body,Auth.userId(request)));}
}
