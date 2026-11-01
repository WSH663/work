package cn.accessible.travel;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.http.converter.HttpMessageNotReadableException;

public final class Api {
    private Api() {}
    public record Result(int code, String message, Object data) {}
    public static Result ok(Object value) { return new Result(0, "ok", value); }
    public static Failure fail(int status, String message) { return new Failure(status, message); }
    public static class Failure extends RuntimeException {
        final int status;
        Failure(int status, String message) { super(message); this.status = status; }
    }
    public static void require(boolean condition, String message) { if (!condition) throw fail(400, message); }
}

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(Api.Failure.class)
    ResponseEntity<Api.Result> handle(Api.Failure e) {
        return ResponseEntity.status(e.status).body(new Api.Result(e.status, e.getMessage(), null));
    }
    @ExceptionHandler({IllegalArgumentException.class, MissingServletRequestParameterException.class, HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<Api.Result> badRequest(Exception e) {
        return ResponseEntity.badRequest().body(new Api.Result(400, "请求参数不正确，请检查必填项与格式", null));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Api.Result> conflict(Exception e) {
        return ResponseEntity.status(409).body(new Api.Result(409, "数据已存在或已被其他操作修改，请刷新后重试", null));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Api.Result> unexpected(Exception e) {
        org.slf4j.LoggerFactory.getLogger(ApiErrors.class).error("Unhandled server error: {}", e.getClass().getName());
        return ResponseEntity.status(500).body(new Api.Result(500, "服务暂时不可用，请稍后重试", null));
    }
}
