/*
 * Copyright 2017 The Mifos Initiative.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.mifos.anubis.test.v1;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.jsonwebtoken.*;
import io.mifos.anubis.annotation.AcceptedTokenType;
import io.mifos.anubis.api.v1.RoleConstants;
import io.mifos.anubis.api.v1.TokenConstants;
import io.mifos.anubis.api.v1.domain.AllowedOperation;
import io.mifos.anubis.api.v1.domain.TokenContent;
import io.mifos.anubis.api.v1.domain.TokenPermission;
import io.mifos.anubis.provider.InvalidKeyVersionException;
import io.mifos.anubis.provider.SystemRsaKeyProvider;
import io.mifos.anubis.security.SystemAuthenticator;
import io.mifos.anubis.service.PermittableService;
import io.mifos.anubis.token.SystemAccessTokenSerializer;
import io.mifos.anubis.token.TenantAccessTokenSerializer;
import io.mifos.core.api.context.AutoSeshat;
import io.mifos.core.api.context.AutoUserContext;
import io.mifos.core.api.util.ApiConstants;
import io.mifos.core.api.util.UserContextHolder;
import io.mifos.core.lang.ApplicationName;
import io.mifos.core.lang.TenantContextHolder;
import io.mifos.core.lang.security.RsaKeyPairFactory;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author Myrle Krantz
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class SystemSecurityEnvironment {
  private final static String LOGGER_NAME = "anubis-test-logger";

  private final TenantAccessTokenSerializer tenantAccessTokenSerializer;
  private final SystemAccessTokenSerializer systemAccessTokenSerializer;
  private final PublicKey seshatPublicKey;
  private final PrivateKey seshatPrivateKey;
  private final Map<String, RsaKeyPairFactory.KeyPairHolder> isisKeyPairHolders;

  public SystemSecurityEnvironment(final PublicKey seshatPublicKey, final PrivateKey seshatPrivateKey) {
    final Gson gson = new GsonBuilder().create();
    this.tenantAccessTokenSerializer = new TenantAccessTokenSerializer(gson);
    this.systemAccessTokenSerializer = new SystemAccessTokenSerializer();
    this.seshatPublicKey = seshatPublicKey;
    this.seshatPrivateKey = seshatPrivateKey;

    this.isisKeyPairHolders = new HashMap<>();
  }

  public AutoUserContext createAutoSeshatContext(final String applicationName)
  {
    return new AutoSeshat(seshatToken(applicationName));
  }

  public AutoUserContext createAutoSeshatContext(final String tenantName, final String applicationName) {
    return new AutoSeshat(seshatToken(tenantName, applicationName));
  }

  public String seshatToken(final String applicationName) {
    return seshatToken(TenantContextHolder.checkedGetIdentifier(), applicationName);
  }

  private String seshatToken(final String tenantName, final String applicationName) {
    return systemAccessTokenSerializer.build(new SystemAccessTokenSerializer.Specification()
            .setTenant(tenantName)
            .setRole(RoleConstants.SYSTEM_ADMIN_ROLE_IDENTIFIER)
            .setSecondsToLive(TimeUnit.HOURS.toSeconds(12))
            .setPrivateKey(seshatPrivateKey)
            .setTargetApplicationName(applicationName)
    ).getToken();
  }

  public AutoUserContext createAutoUserContext(final String userName, final List<String> applicationNames)
  {
    return new AutoUserContext(userName, getEverythingToken(userName, applicationNames));
  }

  private String getEverythingToken(final String userName, final List<String> applicationNames)
  {
    return tenantAccessTokenSerializer.build(new TenantAccessTokenSerializer.Specification()
            .setUser(userName)
            .setTokenContent(getTokenContentForStarEndpoint(applicationNames))
            .setSecondsToLive(TimeUnit.HOURS.toSeconds(10))
            .setPrivateKey(tenantPrivateKey())).getToken();
  }

  public String getPermissionToken(
          final String userName,
          final String applicationName,
          final String uri,
          final AllowedOperation allowedOperation) {
    return tenantAccessTokenSerializer.build(
            new TenantAccessTokenSerializer.Specification().setPrivateKey(tenantPrivateKey())
                    .setSecondsToLive(100)
                    .setUser(userName)
                    .setTokenContent(generateOnePermissionTokenContent(applicationName, uri, allowedOperation))
    ).getToken();
  }

  private TokenContent generateOnePermissionTokenContent(final String applicationName, final String uri, final AllowedOperation allowedOperation) {
    final TokenPermission tokenPermission
            = new TokenPermission(applicationName + uri, Collections.singleton(allowedOperation));

    return new TokenContent(Collections.singletonList(tokenPermission));
  }

  public RSAPublicKey tenantPublicKey()
  {
    return isisKeyPairHolders.computeIfAbsent(TenantContextHolder.checkedGetIdentifier(),
            x -> RsaKeyPairFactory.createKeyPair()).publicKey();
  }

  public RSAPrivateKey tenantPrivateKey()
  {
    return isisKeyPairHolders.computeIfAbsent(TenantContextHolder.checkedGetIdentifier(),
            x -> RsaKeyPairFactory.createKeyPair()).privateKey();
  }

  private TokenContent getTokenContentForStarEndpoint(final List<String> applicationNames) {
    return new TokenContent(
            applicationNames.stream()
            .map(x -> new TokenPermission(x + "/*", AllowedOperation.ALL))
            .collect(Collectors.toList()));
  }

  public boolean isValidGuestSecurityContext(final String forTenant) {
    final boolean validTenant = TenantContextHolder.identifier().map(x -> x.equals(forTenant)).orElse(false);
    final boolean validUser = UserContextHolder.checkedGetUser().equals(RoleConstants.GUEST_USER_IDENTIFIER);
    final boolean validToken = UserContextHolder.checkedGetAccessToken().equals(TokenConstants.NO_AUTHENTICATION);

    return validTenant && validUser && validToken;
  }


  public boolean isValidSystemSecurityContext(final String forService, final String forServiceVersion, final String forTenant) {
    final boolean validTenant = TenantContextHolder.identifier().map(x -> x.equals(forTenant)).orElse(false);
    final boolean validUser = UserContextHolder.checkedGetUser().equals(ApiConstants.SYSTEM_SU);
    final boolean validToken = isValidToken(
            UserContextHolder.checkedGetAccessToken(),
            forService,
            forServiceVersion,
            UserContextHolder.checkedGetUser());

    return validTenant && validUser && validToken;
  }

  public boolean isValidToken(final String token,
                              final String forService,
                              final String forServiceVersion,
                              final String forUser) {
    if (!token.startsWith(TokenConstants.PREFIX))
      return false;

    final String jwtToken = token.substring(TokenConstants.PREFIX.length()).trim();

    final PermittableService permittableService = Mockito.mock(PermittableService.class);
    Mockito.doReturn(Collections.emptySet()).when(permittableService).getPermittableEndpointsAsPermissions(AcceptedTokenType.SYSTEM);

    final SystemRsaKeyProvider systemRsaKeyProvider = Mockito.mock(SystemRsaKeyProvider.class);
    try {
      Mockito.doReturn(seshatPublicKey).when(systemRsaKeyProvider).getPublicKey(Mockito.anyString());
    }
    catch (final InvalidKeyVersionException ignored) {}

    final Logger logger = LoggerFactory.getLogger(LOGGER_NAME);

    final SystemAuthenticator isisSystemAuthenticator = new SystemAuthenticator(
            systemRsaKeyProvider,
            ApplicationName.appNameWithVersion(forService, forServiceVersion),
            permittableService,
            logger);
    try {
      return (isisSystemAuthenticator.authenticate(forUser, jwtToken, "1") != null);
    }
    catch (final Exception e)
    {
      return false;
    }
  }

  public static TokenContent getTokenContent(final String token, final PublicKey publicKey)
  {
    final String strippedAccessToken = token.substring("Bearer ".length());

    final JwtParser parser = Jwts.parser().setSigningKey(publicKey);

    @SuppressWarnings("unchecked") final Jwt<Header, Claims> jwt = parser.parse(strippedAccessToken);

    final String serializedTokenContent = jwt.getBody().get(TokenConstants.JWT_CONTENT_CLAIM, String.class);
    return new Gson().fromJson(serializedTokenContent, TokenContent.class);
  }
}
