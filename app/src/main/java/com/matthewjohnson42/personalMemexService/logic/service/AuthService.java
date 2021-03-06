package com.matthewjohnson42.personalMemexService.logic.service;

import com.matthewjohnson42.personalMemexService.data.dto.AuthRequestDto;
import com.matthewjohnson42.personalMemexService.data.dto.AuthResponseDto;
import com.matthewjohnson42.personalMemexService.data.dto.UserDetailsDto;
import com.matthewjohnson42.personalMemexService.data.mongo.service.UserDetailsMongoService;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import javax.security.auth.login.CredentialException;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;

@Profile("prod")
@Service
public class AuthService {

    Logger logger = LoggerFactory.getLogger(this.getClass());

    MACSigner macSigner;
    PasswordEncoder passwordEncoder;
    UserDetailsMongoService userDetailsMongoService;

    public AuthService(
            MACSigner macSigner,
            PasswordEncoder passwordEncoder,
            UserDetailsMongoService userDetailsMongoService
    ) {
        this.macSigner = macSigner;
        this.passwordEncoder = passwordEncoder;
        this.userDetailsMongoService = userDetailsMongoService;
    }

    public AuthResponseDto processAutheticationRequest(AuthRequestDto authRequestDto) throws JOSEException, CredentialException {
        UserDetailsDto userDetailsDto = userDetailsMongoService.getById(authRequestDto.getUsername());
        String encodedPassword = userDetailsMongoService.getEncodedPasswordForUser(authRequestDto.getUsername());
        if (passwordEncoder.matches(authRequestDto.getPassword(), encodedPassword)) {
            SignedJWT signedJWT = getSignedJwt(userDetailsDto);
            return new AuthResponseDto().setToken(signedJWT.serialize());
        } else {
            logger.error("Failure to authenticate user password");
            throw new CredentialException("Failed to authenticate user password");
        }
    }

    public Collection<GrantedAuthority> getAuthorities(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new HashSet<>();
        UserDetailsDto userDetailsDto = userDetailsMongoService.getById(jwt.getSubject());
        for (String authority: userDetailsDto.getAuthorities()) {
            authorities.add(new SimpleGrantedAuthority(authority));
        }
        return authorities;
    }

    private SignedJWT getSignedJwt(UserDetailsDto userDetailsDto) throws JOSEException {
        JWSHeader jwsHeader = new JWSHeader(JWSAlgorithm.HS256); // move to common location
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer("personal-memex-service")
                .expirationTime(new Date(System.currentTimeMillis() + 3600000))
                .subject(userDetailsDto.getUsername());
        for (String authority: userDetailsDto.getAuthorities()) {
            builder.claim("Authority", authority);
        }
        JWTClaimsSet claims = builder.build();
        SignedJWT signedJWT = new SignedJWT(jwsHeader, claims);
        signedJWT.sign(macSigner);
        return signedJWT;
    }

}
