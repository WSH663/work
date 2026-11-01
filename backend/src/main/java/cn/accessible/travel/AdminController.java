package cn.accessible.travel;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    final Db db;final PlatformService platform;final OrderService orders;final SettingsService settings;final Auth auth;final PromotionService promotion;final AdminTodoService todos;
    AdminController(Db db,PlatformService platform,OrderService orders,SettingsService settings,Auth auth,PromotionService promotion,AdminTodoService todos){this.db=db;this.platform=platform;this.orders=orders;this.settings=settings;this.auth=auth;this.promotion=promotion;this.todos=todos;}
    private void operate(HttpServletRequest request){Auth.roles(request,"ADMIN","OPERATOR");}
    private void finance(HttpServletRequest request){Auth.roles(request,"ADMIN","FINANCE");}
    @GetMapping("/dashboard") public Api.Result dashboard(){
        var result=new LinkedHashMap<String,Object>();
        result.put("todayOrders",db.count("SELECT COUNT(*) FROM orders WHERE created_at>=?",LocalDate.now().atStartOfDay()));
        result.put("totalUsers",db.count("SELECT COUNT(*) FROM users WHERE role='USER'"));
        for(var entry:Map.of("pendingDispatch","PENDING_DISPATCH","pendingQuotes","PENDING_QUOTE","pendingConfirmations","PENDING_CONFIRM","pendingPayments","PENDING_PAYMENT","cancelRequests","CANCEL_REQUESTED","pendingRefunds","REFUNDING","inService","IN_SERVICE").entrySet())
            result.put(entry.getKey(),db.count("SELECT COUNT(*) FROM orders WHERE status=?",entry.getValue()));
        result.put("pendingWithdrawals",db.count("SELECT COUNT(*) FROM withdrawals WHERE status='PENDING'"));
        result.put("approvedWithdrawals",db.count("SELECT COUNT(*) FROM withdrawals WHERE status='APPROVED'"));
        result.put("totalRevenue",db.one("SELECT COALESCE(SUM(paid_amount),0) AS total FROM orders").get("total"));
        result.put("recentOrders",db.list("SELECT * FROM orders ORDER BY id DESC LIMIT 8"));return Api.ok(result);
    }
    @GetMapping("/todos") public Api.Result todos(HttpServletRequest request){Auth.roles(request,"ADMIN","OPERATOR","FINANCE");return Api.ok(todos.summary(Auth.user(request).get("role").toString()));}
    @GetMapping("/orders") public Api.Result orders(@RequestParam(defaultValue="")String status,@RequestParam(defaultValue="")String type,@RequestParam(defaultValue="")String keyword,@RequestParam(defaultValue="")String worklist,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,HttpServletRequest request){
        Api.require(worklist.isBlank()||Set.of("operations","refunds").contains(worklist),"待办筛选不正确");
        if("operations".equals(worklist))operate(request);else if("refunds".equals(worklist))finance(request);
        var args=new ArrayList<Object>();String where=" WHERE 1=1";
        // Worklist scope remains active; additional status/type/search filters narrow it further.
        if("operations".equals(worklist)){where+=" AND "+AdminTodoService.OPERATIONS_WHERE;args.add(LocalDateTime.now());}
        else if("refunds".equals(worklist))where+=" AND status='REFUNDING'";
        if(!status.isBlank()){where+=" AND status=?";args.add(status);}
        if(!type.isBlank()){where+=" AND type=?";args.add(type);}if(!keyword.isBlank()){where+=" AND (order_no LIKE ? OR passenger_name LIKE ? OR passenger_phone LIKE ?)";for(int i=0;i<3;i++)args.add("%"+keyword+"%");}return Api.ok(orders.page(where,args,page,size));}
    @GetMapping("/orders/{id}") public Api.Result order(@PathVariable long id){return Api.ok(orders.get(id,null));}
    @PostMapping("/orders/{id}/quote") public Api.Result quote(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest request){operate(request);return Api.ok(orders.quote(id,Auth.userId(request),body));}
    @PostMapping("/orders/{id}/dispatch") public Api.Result dispatch(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest request){operate(request);return Api.ok(orders.dispatch(id,Auth.userId(request),body));}
    @PostMapping("/orders/{id}/start") public Api.Result start(@PathVariable long id,HttpServletRequest request){operate(request);return Api.ok(orders.start(id,Auth.userId(request)));}
    @PostMapping("/orders/{id}/complete") public Api.Result complete(@PathVariable long id,HttpServletRequest request){operate(request);return Api.ok(orders.complete(id,Auth.userId(request)));}
    @PostMapping("/orders/{id}/terminate") public Api.Result terminate(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest request){operate(request);return Api.ok(orders.terminate(id,Auth.userId(request),body));}
    @PostMapping("/orders/{id}/cancel-review") public Api.Result review(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest request){operate(request);return Api.ok(orders.cancelReview(id,Auth.userId(request),body));}
    @PostMapping("/orders/{id}/cancel") public Api.Result cancel(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest request){operate(request);return Api.ok(orders.cancelByAdmin(id,Auth.userId(request),body));}
    @PostMapping("/orders/{id}/refund") public Api.Result refund(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest request){finance(request);return Api.ok(orders.refund(id,Auth.userId(request),body));}
    @GetMapping("/users") public Api.Result users(@RequestParam(defaultValue="")String keyword,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,HttpServletRequest request){operate(request);var args=new ArrayList<Object>();String where=" WHERE role='USER'";if(!keyword.isBlank()){where+=" AND (nickname LIKE ? OR phone LIKE ? OR invite_code LIKE ?)";for(int i=0;i<3;i++)args.add("%"+keyword+"%");}var result=db.page("users",where,args,page,size);@SuppressWarnings("unchecked")var items=(List<Map<String,Object>>)result.get("items");return Api.ok(Map.of("items",items.stream().map(Auth::safe).toList(),"total",result.get("total"),"page",result.get("page"),"size",result.get("size")));}
    @PatchMapping("/users/{id}") @Transactional public Api.Result userStatus(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest request){operate(request);String status=Inputs.required(body,"status",20);Api.require(Set.of("ACTIVE","DISABLED").contains(status),"账号状态不正确");Api.require(db.count("SELECT COUNT(*) FROM users WHERE id=? AND role='USER'",id)>0,"用户不存在");db.update("UPDATE users SET status=? WHERE id=?",status,id);if("DISABLED".equals(status))db.update("DELETE FROM auth_tokens WHERE user_id=?",id);platform.audit(Auth.userId(request),"USER_STATUS","用户 "+id+" "+status);return Api.ok(Auth.safe(db.one("SELECT * FROM users WHERE id=?",id)));}
    @GetMapping("/vehicles") public Api.Result vehicles(HttpServletRequest request){operate(request);return Api.ok(db.list("SELECT * FROM vehicles ORDER BY id"));}
    @PostMapping("/vehicles") @Transactional public Api.Result addVehicle(@RequestBody Map<String,Object> body,HttpServletRequest request){operate(request);var value=platform.saveVehicle(null,body);platform.audit(Auth.userId(request),"VEHICLE_CREATE","车型 "+value.get("id"));return Api.ok(value);}
    @PatchMapping("/vehicles/{id}") @Transactional public Api.Result vehicle(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest request){operate(request);var value=platform.saveVehicle(id,body);platform.audit(Auth.userId(request),"VEHICLE_UPDATE","车型 "+id);return Api.ok(value);}
    @GetMapping("/content") public Api.Result content(HttpServletRequest request){operate(request);return Api.ok(db.list("SELECT * FROM content ORDER BY sort_order,id"));}
    @PostMapping("/content") @Transactional public Api.Result addContent(@RequestBody Map<String,Object> body,HttpServletRequest request){operate(request);var value=platform.saveContent(null,body);platform.audit(Auth.userId(request),"CONTENT_CREATE","内容 "+value.get("id"));return Api.ok(value);}
    @PatchMapping("/content/{id}") @Transactional public Api.Result content(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest request){operate(request);var value=platform.saveContent(id,body);platform.audit(Auth.userId(request),"CONTENT_UPDATE","内容 "+id);return Api.ok(value);}
    @DeleteMapping("/content/{id}") @Transactional public Api.Result deleteContent(@PathVariable long id,HttpServletRequest request){operate(request);Api.require(db.update("DELETE FROM content WHERE id=?",id)>0,"内容不存在");platform.audit(Auth.userId(request),"CONTENT_DELETE","内容 "+id);return Api.ok(null);}
    @GetMapping("/withdrawals") public Api.Result withdrawals(@RequestParam(defaultValue="")String status,@RequestParam(defaultValue="")String worklist,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,HttpServletRequest request){finance(request);Api.require(worklist.isBlank()||"todo".equals(worklist),"待办筛选不正确");var args=new ArrayList<Object>();String where=" WHERE 1=1";if("todo".equals(worklist))where+=" AND status IN ('PENDING','APPROVED')";if(!status.isBlank()){where+=" AND status=?";args.add(status);}return Api.ok(db.page("withdrawals",where,args,page,size));}
    @PostMapping("/withdrawals/{id}/review") public Api.Result reviewWithdrawal(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest request){finance(request);return Api.ok(promotion.review(id,Auth.userId(request),body));}
    @PostMapping("/withdrawals/{id}/paid") public Api.Result paidWithdrawal(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest request){finance(request);return Api.ok(promotion.paid(id,Auth.userId(request),body));}
    @PostMapping("/withdrawals/{id}/cancel") public Api.Result cancelWithdrawal(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest request){finance(request);return Api.ok(promotion.cancel(id,Auth.userId(request),body));}
    @GetMapping("/commissions") public Api.Result commissions(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,HttpServletRequest request){finance(request);return Api.ok(db.page("commissions","",List.of(),page,size));}
    @GetMapping("/payments") public Api.Result payments(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(defaultValue="")String keyword,HttpServletRequest request){finance(request);String term=keyword.trim();Api.require(term.length()<=80,"订单号查询不能超过80个字符");return Api.ok(db.page("payments",term.isEmpty()?"":" WHERE order_no LIKE ?",term.isEmpty()?List.of():List.of("%"+term+"%"),page,size));}
    @GetMapping("/settings") public Api.Result settings(HttpServletRequest request){Auth.roles(request,"ADMIN");return Api.ok(settings.all());}
    @PostMapping("/settings") @Transactional public Api.Result settings(@RequestBody Map<String,Object> body,HttpServletRequest request){Auth.roles(request,"ADMIN");var result=settings.save(body);platform.audit(Auth.userId(request),"SETTINGS_UPDATE",String.join(",",body.keySet()));return Api.ok(result);}
    @GetMapping("/admins") public Api.Result admins(HttpServletRequest request){Auth.roles(request,"ADMIN");return Api.ok(db.list("SELECT * FROM users WHERE role<>'USER' ORDER BY id").stream().map(Auth::safe).toList());}
    @PostMapping("/admins") @Transactional public Api.Result addAdmin(@RequestBody Map<String,Object> body,HttpServletRequest request){Auth.roles(request,"ADMIN");String username=Inputs.required(body,"username",64),password=Inputs.required(body,"password",200),nickname=Inputs.required(body,"nickname",60),role=Inputs.required(body,"role",20);Api.require(username.matches("[a-zA-Z0-9_.-]{3,64}"),"管理员账号须为3至64位字母数字或_.-");validateAdmin(role,password);long id=db.insert("INSERT INTO users(username,password_hash,nickname,role,invite_code) VALUES (?,?,?,?,?)",username,auth.passwords.encode(password),nickname,role,auth.randomCode());platform.audit(Auth.userId(request),"ADMIN_CREATE","管理员 "+id);return Api.ok(Auth.safe(db.one("SELECT * FROM users WHERE id=?",id)));}
    @PatchMapping("/admins/{id}") @Transactional public Api.Result admin(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest request){Auth.roles(request,"ADMIN");db.list("SELECT id FROM users WHERE role='ADMIN' ORDER BY id FOR UPDATE");var row=db.one("SELECT * FROM users WHERE id=? AND role<>'USER' FOR UPDATE",id);String role=body.containsKey("role")?Inputs.required(body,"role",20):row.get("role").toString();String password=Inputs.str(body,"password",200);validateAdmin(role,password.isEmpty()?"placeholder123":password);boolean enabled=Inputs.bool(body,"enabled","ACTIVE".equals(row.get("status")));
        if("ADMIN".equals(row.get("role"))&&"ACTIVE".equals(row.get("status"))&&(!enabled||!"ADMIN".equals(role)))Api.require(db.count("SELECT COUNT(*) FROM users WHERE role='ADMIN' AND status='ACTIVE'")>1,"必须保留至少一位启用的系统管理员");
        db.update("UPDATE users SET role=?,status=? WHERE id=?",role,enabled?"ACTIVE":"DISABLED",id);if(!password.isEmpty())db.update("UPDATE users SET password_hash=? WHERE id=?",auth.passwords.encode(password),id);db.update("DELETE FROM auth_tokens WHERE user_id=?",id);platform.audit(Auth.userId(request),"ADMIN_UPDATE","管理员 "+id);return Api.ok(Auth.safe(db.one("SELECT * FROM users WHERE id=?",id)));}
    private void validateAdmin(String role,String password){Api.require(Set.of("ADMIN","OPERATOR","FINANCE").contains(role),"管理员角色不正确");Api.require(password.length()>=10&&password.matches(".*[A-Za-z].*")&&password.matches(".*[0-9].*"),"管理员密码至少10位，包含字母和数字");}
    @GetMapping("/logs") public Api.Result logs(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,HttpServletRequest request){Auth.roles(request,"ADMIN");return Api.ok(db.page("audit_logs","",List.of(),page,size));}
}
