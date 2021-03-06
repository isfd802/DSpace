/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.rest.authentication;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.authenticate.AuthenticationMethod;
import org.dspace.authenticate.factory.AuthenticateServiceFactory;
import org.dspace.authenticate.service.AuthenticationService;
import org.dspace.core.Context;
import org.dspace.core.LogHelper;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.Group;
import org.dspace.utils.DSpace;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The core authentication & authorization provider, this provider is called when logging in & will process
 *
 * @author Roeland Dillen (roeland at atmire dot com)
 * @author kevinvandevelde at atmire.com
 *
 * FIXME This provider handles both the authorization as well as the authentication,
 * due to the way that the DSpace authentication is implemented there is currently no other way to do this.
 */
public class DSpaceAuthenticationProvider implements AuthenticationProvider {

    private static final Logger log = LogManager.getLogger();

    protected AuthenticationService authenticationService = AuthenticateServiceFactory.getInstance()
                                                                                      .getAuthenticationService();

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        Context context = null;

        try {
            context = new Context();
            String name = authentication.getName();
            String password = authentication.getCredentials().toString();
            HttpServletRequest httpServletRequest = new DSpace().getRequestService().getCurrentRequest()
                                                                .getHttpServletRequest();
            List<SimpleGrantedAuthority> grantedAuthorities = new ArrayList<>();


            int implicitStatus = authenticationService
                .authenticateImplicit(context, null, null, null, httpServletRequest);

            if (implicitStatus == AuthenticationMethod.SUCCESS) {
                log.info(LogHelper.getHeader(context, "login", "type=implicit"));
                addSpecialGroupsToGrantedAuthorityList(context, httpServletRequest, grantedAuthorities);
                return createAuthenticationToken(password, context, grantedAuthorities);

            } else {
                int authenticateResult = authenticationService
                    .authenticate(context, name, password, null, httpServletRequest);
                if (AuthenticationMethod.SUCCESS == authenticateResult) {
                    addSpecialGroupsToGrantedAuthorityList(context, httpServletRequest, grantedAuthorities);

                    log.info(LogHelper.getHeader(context, "login", "type=explicit"));

                    return createAuthenticationToken(password, context, grantedAuthorities);

                } else {
                    log.info(LogHelper.getHeader(context, "failed_login",
                            "email=" + name + ", result=" + authenticateResult));
                    throw new BadCredentialsException("Login failed");
                }
            }
        } catch (BadCredentialsException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error while authenticating in the rest api", e);
        } finally {
            if (context != null && context.isValid()) {
                try {
                    context.complete();
                } catch (SQLException e) {
                    log.error(e.getMessage() + " occurred while trying to close", e);
                }
            }
        }

        return null;
    }

    protected void addSpecialGroupsToGrantedAuthorityList(Context context, HttpServletRequest httpServletRequest,
                                                          List<SimpleGrantedAuthority> grantedAuthorities)
        throws SQLException {
        List<Group> groups = authenticationService.getSpecialGroups(context, httpServletRequest);
        for (Group group : groups) {
            grantedAuthorities.add(new SimpleGrantedAuthority(group.getName()));
        }
    }

    private Authentication createAuthenticationToken(final String password, final Context context,
                                                     final List<SimpleGrantedAuthority> grantedAuthorities) {
        EPerson ePerson = context.getCurrentUser();
        if (ePerson != null && StringUtils.isNotBlank(ePerson.getEmail())) {
            return new UsernamePasswordAuthenticationToken(ePerson.getEmail(), password, grantedAuthorities);

        } else {
            log.info(LogHelper.getHeader(context, "failed_login",
                    "No eperson with an non-blank e-mail address found"));
            throw new BadCredentialsException("Login failed");
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return (UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication));
    }
}