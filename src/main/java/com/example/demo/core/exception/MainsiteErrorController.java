package com.example.demo.core.exception;

import com.example.demo.common.enmu.SystemError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MainsiteErrorController implements ErrorController {
    private static final String ERROR_PATH = "/error";

    @RequestMapping(value=ERROR_PATH)
    public void handleError(HttpServletRequest request, HttpServletResponse response, Exception e) {
    	HttpStatus status = getStatus(request);
    	Throwable throwable = (Throwable)request.getAttribute("javax.servlet.error.exception");
    	if (throwable != null) {
    		String message = throwable.getMessage();
		} else {
            SystemError result = SystemError.getDefined(status.value());
			throw new BusinessException(result);
		}
    }

    /**
     * 获取错误编码
     * @param request
     * @return
     */
    private HttpStatus getStatus(HttpServletRequest request) {
        Integer statusCode = (Integer) request
                .getAttribute("javax.servlet.error.status_code");
        if (statusCode == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        try {
            return HttpStatus.valueOf(statusCode);
        } catch (Exception ex) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
    }

    // @Override
    public String getErrorPath() {
        return ERROR_PATH;
    }
}