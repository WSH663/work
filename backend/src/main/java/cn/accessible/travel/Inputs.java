package cn.accessible.travel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Map;

public final class Inputs {
    private Inputs() {}
    public static String str(Map<String,Object> data,String key,int max) { Object value=data.get(key);String text=value==null?"":String.valueOf(value).trim();Api.require(text.length()<=max,key+"长度不能超过"+max);return text; }
    public static String required(Map<String,Object> data,String key,int max) { String value=str(data,key,max);Api.require(!value.isEmpty(),key+"不能为空");return value; }
    public static long id(Map<String,Object> data,String key) { try {long value=Long.parseLong(required(data,key,20));Api.require(value>0,key+"不正确");return value;}catch(NumberFormatException e){throw Api.fail(400,key+"不正确");} }
    public static BigDecimal decimal(Map<String,Object> data,String key,BigDecimal fallback) { String value=str(data,key,24);if(value.isEmpty())return fallback;try{return new BigDecimal(value);}catch(NumberFormatException e){throw Api.fail(400,key+"必须是数字");} }
    public static BigDecimal money(Map<String,Object> data,String key) { BigDecimal value=decimal(data,key,null);Api.require(value!=null&&value.signum()>0&&value.compareTo(new BigDecimal("999999.99"))<=0,key+"必须为0.01至999999.99元");Api.require(value.scale()<=2,key+"最多两位小数");return value.setScale(2); }
    public static String phone(Map<String,Object> data,String key) { String value=required(data,key,20);Api.require(value.matches("1[3-9]\\d{9}"),"请填写有效的中国大陆手机号");return value; }
    public static boolean bool(Map<String,Object> data,String key,boolean fallback) { Object value=data.get(key);if(value==null)return fallback;Api.require(value instanceof Boolean,key+"必须是布尔值");return (boolean)value; }
    public static int integer(Map<String,Object> data,String key,int fallback,int min,int max){ BigDecimal value=decimal(data,key,BigDecimal.valueOf(fallback));try{int n=value.intValueExact();Api.require(n>=min&&n<=max,key+"超出范围");return n;}catch(ArithmeticException e){throw Api.fail(400,key+"必须为整数");} }
    public static BigDecimal number(Map<String,Object> row,String key) {Object value=row.get(key);return value==null?BigDecimal.ZERO:new BigDecimal(value.toString());}
    public static long longValue(Map<String,Object> row,String key) {return ((Number)row.get(key)).longValue();}
    public static double[] location(String value) { try {var parts=value.split(",");Api.require(parts.length==2,"经纬度格式为经度,纬度");double lng=Double.parseDouble(parts[0]),lat=Double.parseDouble(parts[1]);Api.require(Double.isFinite(lng)&&Double.isFinite(lat)&&lng>=73&&lng<=136&&lat>=3&&lat<=54,"地点须位于中国境内");return new double[]{lng,lat};}catch(NumberFormatException e){throw Api.fail(400,"经纬度格式不正确");} }
    public static LocalDateTime time(Map<String,Object> data,String key) { try { return LocalDateTime.parse(required(data,key,40)); }catch(java.time.format.DateTimeParseException e) {throw Api.fail(400,"预约时间格式不正确");} }
}
