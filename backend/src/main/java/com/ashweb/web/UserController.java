package com.ashweb.web;

import com.ashweb.security.UserRepository;
import com.ashweb.security.UserRepository.AppUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** User administration. The whole path is restricted to admins in SecurityConfig. */
@RestController
@RequestMapping("/api/users")
public class UserController {

    public record UserView(long id, String username, String role, boolean enabled, Instant createdAt) {
    }

    public record CreateUser(
            @NotBlank @Size(max = 60) @Pattern(regexp = "[A-Za-z0-9._@-]+", message = "may only contain letters, digits and . _ @ -")
            String username,
            @NotBlank @Size(min = 8, max = 200) String password,
            @NotBlank @Pattern(regexp = "ADMIN|VIEWER") String role) {
    }

    public record UpdateUser(
            @NotBlank @Pattern(regexp = "ADMIN|VIEWER") String role,
            @NotNull Boolean enabled,
            @Size(min = 8, max = 200) String password) {
    }

    private final UserRepository users;
    private final PasswordEncoder encoder;

    public UserController(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    private static UserView view(AppUser u) {
        return new UserView(u.id(), u.username(), u.role(), u.enabled(), u.createdAt());
    }

    @GetMapping
    public List<UserView> list() {
        return users.findAll().stream().map(UserController::view).toList();
    }

    @PostMapping
    public UserView create(@Valid @RequestBody CreateUser body) {
        if (users.findByUsername(body.username()).isPresent()) {
            throw ApiException.badRequest("A user named '" + body.username() + "' already exists");
        }
        long id = users.insert(body.username(), encoder.encode(body.password()), body.role());
        return view(users.findById(id).orElseThrow());
    }

    @PutMapping("/{id}")
    public UserView update(@PathVariable long id, @Valid @RequestBody UpdateUser body, Authentication me) {
        AppUser user = users.findById(id).orElseThrow(() -> ApiException.notFound("User not found"));
        boolean losesAdmin = "ADMIN".equals(user.role()) && user.enabled()
                && (!"ADMIN".equals(body.role()) || !body.enabled());
        if (losesAdmin) {
            if (user.username().equals(me.getName())) {
                throw ApiException.badRequest("You cannot remove your own admin access");
            }
            requireAnotherAdmin();
        }
        users.update(id, body.role(), body.enabled());
        if (body.password() != null && !body.password().isBlank()) {
            users.updatePassword(id, encoder.encode(body.password()));
        }
        return view(users.findById(id).orElseThrow());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id, Authentication me) {
        AppUser user = users.findById(id).orElseThrow(() -> ApiException.notFound("User not found"));
        if (user.username().equals(me.getName())) {
            throw ApiException.badRequest("You cannot delete your own account");
        }
        if ("ADMIN".equals(user.role()) && user.enabled()) {
            requireAnotherAdmin();
        }
        users.delete(id);
    }

    private void requireAnotherAdmin() {
        if (users.countEnabledAdmins() <= 1) {
            throw ApiException.badRequest("At least one enabled admin must remain");
        }
    }
}
