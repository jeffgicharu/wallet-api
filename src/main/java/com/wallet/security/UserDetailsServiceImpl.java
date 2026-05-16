package com.wallet.security;

import com.wallet.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        var user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));

        // Grant the persisted role as a ROLE_ authority so
        // hasRole('ADMIN') / @PreAuthorize works (issue #2). Sourced from
        // the DB on every request, so a role change takes effect on the
        // next call without waiting for the JWT to expire.
        var authority = new SimpleGrantedAuthority("ROLE_" + user.getRole().name());

        return new User(user.getEmail(), user.getPassword(), List.of(authority));
    }
}
