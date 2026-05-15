package com.gatekeeper.auth.service;

import com.gatekeeper.auth.entity.User;
import io.jsonwebtoken.security.Password;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

@Service
public class UserService {

    private final PasswordEncoder passwordEncoder;

    public UserService(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    private final ConcurrentHashMap<String, User> users = new ConcurrentHashMap<>();

    public void register(String username, String password) {
        String hashedPassword = passwordEncoder.encode(password);
        User newUser = new User(username, hashedPassword);
        users.put(username, newUser);
    }

    public User findByUsername(String username) {
        return users.get(username);
    }
}
