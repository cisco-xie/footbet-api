package com.example.demo.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminLoginDTO {
    private String username;
    private String nickname;
    private String password;
    private List<String> roles;
    private List<String> permissions;
    private String accessToken;
    private String refreshToken;
    private String expires;
    private Integer autoBet = 0;
    private String group;
}
