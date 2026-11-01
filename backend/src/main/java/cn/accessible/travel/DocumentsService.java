package cn.accessible.travel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentsService {
    public record Section(String id,String title,String body,String mediaType,String mediaUrl,String posterUrl,String linkUrl,List<String> actions,Boolean enabled) {}
    public record Document(String title,String introduction,String footerTitle,String footer,List<Section> sections) {}
    public record SaveRequest(Long revision,Document document) {}
    public record PublishRequest(Long revision) {}
    public record State(String kind,Document draft,Document published,long revision,long publishedRevision,LocalDateTime updatedAt,LocalDateTime publishedAt,Map<String,String> variables) {}
    public record Published(Document document,long revision,LocalDateTime publishedAt) {}
    private record Stored(String kind,Document draft,Document published,long revision,long publishedRevision,LocalDateTime updatedAt,LocalDateTime publishedAt) {}
    private static final Set<String> KINDS=Set.of("service","privacy","terms");
    private static final Set<String> ACTIONS=Set.of("contact","profile","passengers","addresses","amapPrivacy");
    private static final Set<String> LINKS=Set.of(
        "/pages/index/index","/pages/booking/index","/pages/booking/index?type=RIDE","/pages/booking/index?type=CHARTER",
        "/pages/orders/index","/pages/profile/index","/pages/promotion/index","/pages/messages/index",
        "/pages/agreement/index","/pages/agreement/index?kind=service","/pages/agreement/index?kind=notice",
        "/pages/agreement/index?kind=terms","/pages/agreement/index?kind=privacy");
    private static final Set<String> VARIABLES=Set.of("serviceArea","servicePhone","advanceMinutes","maxAdvanceDays","cancelHours","advanceRule","bookingRangeRule","cancellationRule");
    private static final Pattern TOKEN=Pattern.compile("\\{\\{([a-zA-Z][a-zA-Z0-9]*)\\}\\}");
    final Db db;final SettingsService settings;final ObjectMapper json;final PlatformService platform;
    DocumentsService(Db db,SettingsService settings,ObjectMapper json,PlatformService platform){this.db=db;this.settings=settings;this.json=json;this.platform=platform;}

    public State get(String kind){return state(read(kind,false));}
    public Published published(String kind) {
        var row=read(kind,false);var variables=variables();
        Document source=row.published();
        var sections=source.sections().stream().filter(s->Boolean.TRUE.equals(s.enabled())).map(s->new Section(s.id(),render(s.title(),variables),render(s.body(),variables),s.mediaType(),s.mediaUrl(),s.posterUrl(),s.linkUrl(),s.actions(),true)).toList();
        return new Published(new Document(render(source.title(),variables),render(source.introduction(),variables),render(source.footerTitle(),variables),render(source.footer(),variables),sections),row.publishedRevision(),row.publishedAt());
    }
    @Transactional public State save(String kind,SaveRequest request,long actor) {
        Api.require(request!=null&&request.revision()!=null&&request.revision()>0,"请提供当前草稿版本");
        var row=read(kind,true);requireRevision(row,request.revision());
        var document=validate(request.document());var now=LocalDateTime.now();
        db.update("UPDATE documents SET draft_json=?,revision=revision+1,updated_at=? WHERE kind=?",encode(document),now,kind);
        platform.audit(actor,"DOCUMENT_DRAFT_SAVE",kind+" 草稿版本 "+(row.revision()+1));
        return state(read(kind,false));
    }
    @Transactional public State publish(String kind,PublishRequest request,long actor) {
        Api.require(request!=null&&request.revision()!=null&&request.revision()>0,"请提供当前草稿版本");
        var row=read(kind,true);requireRevision(row,request.revision());
        if(row.publishedRevision()==row.revision())return state(row);
        Document document=validate(row.draft());
        Api.require(document.sections().stream().anyMatch(s->Boolean.TRUE.equals(s.enabled())),"发布时至少启用一个章节");
        var now=LocalDateTime.now();
        db.update("UPDATE documents SET published_json=?,published_revision=?,published_at=? WHERE kind=?",encode(document),row.revision(),now,kind);
        platform.audit(actor,"DOCUMENT_PUBLISH",kind+" 发布版本 "+row.revision());
        return state(read(kind,false));
    }
    private void requireRevision(Stored row,long revision){if(row.revision()!=revision)throw Api.fail(409,"文档已被其他操作修改，请刷新后重试");}
    private State state(Stored row){return new State(row.kind(),row.draft(),row.published(),row.revision(),row.publishedRevision(),row.updatedAt(),row.publishedAt(),variables());}
    private Stored read(String kind,boolean lock) {
        if(!KINDS.contains(kind))throw Api.fail(404,"文档不存在");
        var values=db.jdbc.query("SELECT * FROM documents WHERE kind=?"+(lock?" FOR UPDATE":""),(rs,n)->new Stored(rs.getString("kind"),decode(rs.getString("draft_json")),decode(rs.getString("published_json")),rs.getLong("revision"),rs.getLong("published_revision"),rs.getTimestamp("updated_at").toLocalDateTime(),rs.getTimestamp("published_at").toLocalDateTime()),kind);
        if(values.isEmpty())throw Api.fail(404,"文档尚未初始化，请联系管理员完成数据库升级");
        return values.get(0);
    }
    private Document decode(String source){try{return json.readValue(source,Document.class);}catch(JsonProcessingException e){throw new IllegalStateException("Stored document is invalid",e);}}
    private String encode(Document document){try{return json.writeValueAsString(document);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}

    Document validate(Document source) {
        Api.require(source!=null,"请填写文档内容");
        String title=text(source.title(),120,"文档标题",true),intro=text(source.introduction(),2000,"引言",false),footerTitle=text(source.footerTitle(),120,"页尾标题",false),footer=text(source.footer(),4000,"页尾说明",false);
        Api.require(source.sections()!=null&&!source.sections().isEmpty()&&source.sections().size()<=30,"文档须包含1至30个章节");
        var sections=new ArrayList<Section>();var ids=new HashSet<String>();int total=title.length()+intro.length()+footerTitle.length()+footer.length();
        for(Section section:source.sections()) {
            Api.require(section!=null,"章节内容不正确");String id=section.id()==null?"":section.id();
            Api.require(id.matches("[a-z][a-z0-9-]{0,63}")&&ids.add(id),"章节标识须为不重复的小写字母开头的字母、数字或短横线，最多64位");
            String heading=text(section.title(),120,"章节标题",true),body=text(section.body(),8000,"章节正文",false);
            Api.require(Set.of("TEXT","IMAGE","VIDEO").contains(Objects.toString(section.mediaType(),"")),"章节媒体类型不正确");
            String media=url(section.mediaUrl(),"媒体地址"),poster=url(section.posterUrl(),"视频封面"),link=link(section.linkUrl());
            Api.require("TEXT".equals(section.mediaType())||!media.isEmpty(),"图片或视频章节须填写媒体地址");
            Api.require(!body.isEmpty()||(!"TEXT".equals(section.mediaType())&&!media.isEmpty()),"章节须填写正文或图片、视频内容");
            Api.require(section.enabled()!=null,"请设置章节是否启用");
            var actions=section.actions()==null?List.<String>of():section.actions();
            Api.require(actions.size()<=ACTIONS.size(),"章节操作过多");var unique=new LinkedHashSet<String>();
            for(String action:actions)Api.require(ACTIONS.contains(Objects.toString(action,""))&&unique.add(action),"章节操作不支持或重复");
            total+=heading.length()+body.length();
            sections.add(new Section(id,heading,body,section.mediaType(),"TEXT".equals(section.mediaType())?"":media,"VIDEO".equals(section.mediaType())?poster:"",link,List.copyOf(unique),section.enabled()));
        }
        Api.require(total<=60000,"整篇文档文字不能超过60000字");
        return new Document(title,intro,footerTitle,footer,List.copyOf(sections));
    }
    private String text(String value,int max,String label,boolean required) {
        String normalized=value==null?"":value.trim();
        Api.require(normalized.length()<=max,label+"不能超过"+max+"字");
        Api.require(!required||!normalized.isEmpty(),"请填写"+label);
        Api.require(normalized.indexOf('\0')<0,label+"包含无效字符");
        var matcher=TOKEN.matcher(normalized);while(matcher.find())Api.require(VARIABLES.contains(matcher.group(1)),label+"包含未知变量："+matcher.group(1));
        String stripped=matcher.replaceAll("");Api.require(!stripped.contains("{{")&&!stripped.contains("}}"),label+"变量格式不正确，请使用 {{变量名}}");
        return normalized;
    }
    private String url(String value,String label) {
        String normalized=value==null?"":value.trim();Api.require(normalized.length()<=1000,label+"不能超过1000字");
        if(normalized.isEmpty())return normalized;
        try{var uri=URI.create(normalized);Api.require("https".equalsIgnoreCase(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null&&uri.getFragment()==null,label+"须为有效HTTPS链接");}
        catch(IllegalArgumentException e){throw Api.fail(400,label+"须为有效HTTPS链接");}
        return normalized;
    }
    private String link(String value) {
        String normalized=value==null?"":value.trim();Api.require(normalized.length()<=1000,"相关页面地址不能超过1000字");if(normalized.isEmpty())return normalized;
        // The miniapp navigates the stored string directly. Accept canonical routes only,
        // so an encoded path cannot pass validation and then become an unusable button.
        Api.require(LINKS.contains(normalized),"相关页面仅支持已开放的小程序页面及参数");
        return normalized;
    }
    public Map<String,String> variables() {
        var values=settings.all();var result=new LinkedHashMap<String,String>();
        result.put("serviceArea",Objects.toString(values.getOrDefault("serviceArea",values.getOrDefault("serviceCity","")),""));
        result.put("servicePhone",Objects.toString(values.get("servicePhone"),""));
        for(String key:List.of("advanceMinutes","maxAdvanceDays","cancelHours"))result.put(key,number(values.get(key)));
        String advance=result.get("advanceMinutes"),days=result.get("maxAdvanceDays"),hours=result.get("cancelHours");
        result.put("advanceRule",advance.isEmpty()?"可预约时间以预约页面的当前规则为准。":advance.equals("0")?"预约时间须晚于当前时间。":"请至少提前 "+advance+" 分钟预约。");
        result.put("bookingRangeRule",days.isEmpty()?"":"当前可预约未来 "+days+" 天内的行程。");
        result.put("cancellationRule",hours.isEmpty()?"符合平台线上取消时限时，可在订单详情填写原因并申请取消。":hours.equals("0")?"预约时间前，可按订单详情提示填写原因并申请取消。":"当前已付款订单的线上取消，需至少在预约时间前 "+hours+" 小时申请，并填写原因。");
        return Collections.unmodifiableMap(result);
    }
    private String number(Object value){if(value==null)return "";try{return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();}catch(NumberFormatException e){return "";}}
    private String render(String text,Map<String,String> values){return TOKEN.matcher(text).replaceAll(match->java.util.regex.Matcher.quoteReplacement(values.getOrDefault(match.group(1),match.group())));}
}
