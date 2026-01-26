package com.smancode.smanagent.common.service_discovery.model;

import com.smancode.smanagent.common.service_discovery.model.ServiceInstance;

/**
 * 服务注册响应
 */
public class RegisterResponse {
    private boolean success;
    private String message;
    private String errorCode;
    private ServiceInstance data;

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public ServiceInstance getData() {
        return data;
    }

    public void setData(ServiceInstance data) {
        this.data = data;
    }
}
