package cn.accessible.travel;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

@Component
public class Db {
    final JdbcTemplate jdbc;
    public Db(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<Map<String,Object>> list(String sql, Object... args) { return jdbc.query(sql, Db::row, args); }
    public Map<String,Object> one(String sql, Object... args) {
        List<Map<String,Object>> rows = list(sql, args);
        if (rows.isEmpty()) throw Api.fail(404, "记录不存在");
        return rows.get(0);
    }
    public Map<String,Object> maybe(String sql, Object... args) { var rows = list(sql,args); return rows.isEmpty()? null:rows.get(0); }
    public int update(String sql, Object... args) { return jdbc.update(sql,args); }
    public long insert(String sql, Object... args) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> { var statement = connection.prepareStatement(sql, new String[]{"id"}); for(int i=0;i<args.length;i++) statement.setObject(i+1,args[i]); return statement; }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }
    public long count(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class,args)); }
    public Map<String,Object> page(String tableSql, String where, List<Object> args, int page, int size) {
        page=Math.max(1,page);size=Math.max(1,Math.min(100,size));
        long total=count("SELECT COUNT(*) FROM " + tableSql + where,args.toArray());
        var paged=new ArrayList<>(args); paged.add(size);paged.add((page-1)*size);
        return Map.of("items",list("SELECT * FROM " + tableSql + where + " ORDER BY id DESC LIMIT ? OFFSET ?",paged.toArray()),"total",total,"page",page,"size",size);
    }
    private static Map<String,Object> row(ResultSet result, int ignored) throws SQLException {
        var map=new LinkedHashMap<String,Object>(); var meta=result.getMetaData();
        for(int i=1;i<=meta.getColumnCount();i++) {
            String label=meta.getColumnLabel(i).toLowerCase(Locale.ROOT); var key=new StringBuilder();boolean upper=false;
            for(char c:label.toCharArray()) { if(c=='_') upper=true; else {key.append(upper?Character.toUpperCase(c):c);upper=false;} }
            Object value=result.getObject(i); if(value instanceof Timestamp timestamp) value=timestamp.toLocalDateTime();
            map.put(key.toString(),value);
        } return map;
    }
}
