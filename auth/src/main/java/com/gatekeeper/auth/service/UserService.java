package com.gatekeeper.auth.service;

import com.gatekeeper.auth.model.User;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

@Service
public class UserService {

    private final ConcurrentHashMap<String, User> users = new ConcurrentHashMap<>();

    public void register(User user) {
        users.put(user.getUsername(), user);
    }

    public User findByUsername(String username) {
        return users.get(username);
    }
}
