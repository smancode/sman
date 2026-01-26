package com.smancode.smanagent.common.service_discovery.model;

import com.smancode.smanagent.common.service_discovery.model.ServiceInstance;

/**
 * 注册结果
 */
public class RegisterResult {
    private boolean success;
    private String message;
    private ServiceInstance data;

    public static RegisterResult success(ServiceInstance instance) {
        RegisterResult result = new RegisterResult();
        result.setSuccess(true);
        result.setMessage("服务注册成功");
        result.setData(instance);
        return result;
    }

    public static RegisterResult failure(String message) {
        RegisterResult result = new RegisterResult();
        result.setSuccess(false);
        result.setMessage(message);
        return result;
    }

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

    public ServiceInstance getData() {
        return data;
    }

    public void setData(ServiceInstance data) {
        this.data = data;
    }
}
