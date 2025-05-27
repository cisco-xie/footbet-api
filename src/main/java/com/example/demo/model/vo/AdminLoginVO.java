package com.example.demo.model.vo;

import lombok.Data;

@Data
public class AdminLoginVO {
    private String username;
    private String password;
    private String group;
    private String roles;
}
