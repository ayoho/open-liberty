-include= ~${workspace}/cnf/resources/bnd/feature.props
symbolicName=io.openliberty.mpJwtBridge-1.0
visibility=public
singleton=true
IBM-API-Package: jakarta.security.enterprise; type="spec", \
 jakarta.security.enterprise.authentication.mechanism.http; type="spec", \
 jakarta.security.enterprise.authentication.mechanism.http.openid; type="spec", \
 jakarta.security.enterprise.credential; type="spec", \
 jakarta.security.enterprise.identitystore; type="spec", \
 jakarta.security.enterprise.identitystore.openid; type="spec", \
 jakarta.security.auth.message; type="spec", \
 jakarta.security.auth.message.callback; type="spec", \
 jakarta.security.auth.message.config; type="spec", \
 jakarta.security.auth.message.module; type="spec", \
 com.ibm.wsspi.security.tai; type="ibm-api", \
 com.ibm.wsspi.security.token; type="ibm-api", \
 com.ibm.wsspi.security.auth.callback; type="ibm-api", \
 com.ibm.wsspi.security.common.auth.module; type="ibm-api", \
 com.ibm.websphere.security.auth.callback; type="ibm-api", \
 com.ibm.websphere.security.web; type="ibm-api"

IBM-ShortName: mpJwtBridge-1.0
Subsystem-Name: MicroProfile JWT Bridge 1.0
-features=io.openliberty.cdi-4.0, \
  com.ibm.websphere.appserver.eeCompatible-10.0, \
  com.ibm.websphere.appserver.security-1.0, \
  io.openliberty.securityAPI.jakarta-1.0, \
  io.openliberty.jakarta.security.enterprise-3.0, \
  io.openliberty.expressionLanguage-5.0, \
  io.openliberty.jsonp-2.1, \
  io.openliberty.webAppSecurity-2.0
-bundles=\
  com.ibm.json4j, \
  com.ibm.ws.org.apache.commons.lang3, \
  com.ibm.ws.org.apache.httpcomponents, \
  com.ibm.ws.org.jose4j, \
  com.ibm.ws.security.common.jsonwebkey, \
  io.openliberty.org.apache.commons.codec, \
  io.openliberty.org.apache.commons.logging, \
  io.openliberty.security.common.internal, \
  io.openliberty.security.mp.jwt.bridge.internal, \
  io.openliberty.security.mp.jwt.bridge.internal.cdi, \
  io.openliberty.security.sso.internal, \
  io.openliberty.security.jaspic.2.0.internal, \
  io.openliberty.security.common.jwt.internal
kind=noship
edition=core
WLP-InstantOn-Enabled: true
