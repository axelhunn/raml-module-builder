package org.folio.rest.tools;

import com.sun.codemodel.JBlock; //NOSONAR - suppress "Use classes from the Java API instead of Sun classes."
import com.sun.codemodel.JClass;
import com.sun.codemodel.JCodeModel;
import com.sun.codemodel.JConditional;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JDocComment;
import com.sun.codemodel.JExpr;
import com.sun.codemodel.JExpression;
import com.sun.codemodel.JFieldVar;
import com.sun.codemodel.JForLoop;
import com.sun.codemodel.JMethod;
import com.sun.codemodel.JMod;
import com.sun.codemodel.JPackage;
import com.sun.codemodel.JVar;
import com.sun.codemodel.JWhileLoop;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMultipart;

import org.apache.commons.io.FileUtils;

/**
 *
 */
public class ClientGenerator {

  public static final String  PATH_ANNOTATION        = "javax.ws.rs.Path";
  public static final String  CLIENT_CLASS_SUFFIX    = "Client";
  @SuppressWarnings("squid:S1075")  // suppress "URIs should not be hardcoded"
  public static final String  PATH_TO_GENERATE_TO    = "/target/generated-sources/raml-jaxrs/";
  public static final String  OKAPI_HEADER_TENANT    = "x-okapi-tenant";
  public static final String  APPEND                 = "append";
  public static final String  TENANT_ID              = "tenantId";
  public static final String  TOKEN                  = "token";
  private static final String KEEP_ALIVE             = "keepAlive";
  private static final String OKAPI_URL              = "okapiUrl";

  private static final Logger log = LoggerFactory.getLogger(ClientGenerator.class);

  /* Creating java code model classes */
  JCodeModel jcodeModel = new JCodeModel();

  /* for creating the class per interface */
  JDefinedClass jc = null;

  private List<String> functionSpecificHeaderParams = new ArrayList<>();

  private String className = null;

  private String mappingType = "postgres";

  private JFieldVar tenantId;
  private JFieldVar okapiUrl;

  public static void main(String[] args) throws Exception {

    String dir = System.getProperties().getProperty("project.basedir")
        + ClientGenerator.PATH_TO_GENERATE_TO
        + RTFConsts.CLIENT_GEN_PACKAGE.replace('.', '/');

    makeCleanDir(dir);

    AnnotationGrabber.generateMappings();

  }

  /**
   * Add a comment to the body of the method saying that it is auto-generated and how.
   * @param method  where to add the comment
   */
  private void addCommentAutogenerated(JMethod method) {
    String resourceName = RTFConsts.INTERFACE_PACKAGE + "." + className + "Resource";
    method.body().directStatement("// Auto-generated code");
    method.body().directStatement("// - generated by       " + getClass().getCanonicalName());
    method.body().directStatement("// - generated based on " + resourceName);
  }

  /**
   * Create a constructor and add a comment to the body of the constructor saying that it
   * is auto-generated and how.
   * @return the new constructor
   */
  private JMethod constructor() {
    JMethod constructor = jc.constructor(JMod.PUBLIC);
    addCommentAutogenerated(constructor);
    return constructor;
  }

  private void deprecate(JMethod method) {
    method.annotate(Deprecated.class);
    method.javadoc().add("@deprecated  use a constructor that takes a full okapiUrl instead");
  }

  private void addConstructor0Args() {
    JMethod constructor = constructor();
    JBlock conBody = constructor.body();
    conBody.invoke("this").arg("localhost").arg(JExpr.lit(8081)).arg("folio_demo").arg("folio_demo").arg(JExpr.FALSE)
      .arg(JExpr.lit(2000)).arg(JExpr.lit(5000));
    constructor.javadoc().add("Convenience constructor for tests ONLY!<br>Connect to localhost on 8081 as folio_demo tenant.");
    deprecate(constructor);
  }

  private void addConstructor4Args() {
    JMethod constructor = constructor();
    JVar hostVar = constructor.param(String.class, "host");
    JVar portVar = constructor.param(int.class, "port");
    JVar tenantIdVar = constructor.param(String.class, TENANT_ID);
    JVar tokenVar = constructor.param(String.class, TOKEN);
    JBlock conBody = constructor.body();
    conBody.invoke("this").arg(hostVar).arg(portVar).arg(tenantIdVar).arg(tokenVar).arg(JExpr.TRUE)
      .arg(JExpr.lit(2000)).arg(JExpr.lit(5000));
    deprecate(constructor);
  }

  private void addConstructor5Args() {
    JMethod constructor = constructor();
    JVar hostVar = constructor.param(String.class, "host");
    JVar portVar = constructor.param(int.class, "port");
    JVar tenantIdVar = constructor.param(String.class, TENANT_ID);
    JVar tokenVar = constructor.param(String.class, TOKEN);
    JVar keepAlive = constructor.param(boolean.class, KEEP_ALIVE);
    JBlock conBody = constructor.body();
    conBody.invoke("this").arg(hostVar).arg(portVar).arg(tenantIdVar).arg(tokenVar).arg(keepAlive)
      .arg(JExpr.lit(2000)).arg(JExpr.lit(5000));
    deprecate(constructor);
  }

  /** "http://" + host + ":" + port */
  private JExpression okapiUrl(JVar host, JVar port) {
    return JExpr.lit("http://").plus(host).plus(JExpr.lit(":")).plus(port);
  }

  private void addConstructor7Args() {
    /* constructor, init the httpClient - allow to pass keep alive option */
    JMethod constructor = constructor();
    JVar host = constructor.param(String.class, "host");
    JVar port = constructor.param(int.class, "port");
    JVar tenantIdVar = constructor.param(String.class, TENANT_ID);
    JVar tokenVar = constructor.param(String.class, TOKEN);
    JVar keepAlive = constructor.param(boolean.class, KEEP_ALIVE);
    JVar connTimeout = constructor.param(int.class, "connTO");
    JVar idleTimeout = constructor.param(int.class, "idleTO");

    JBlock conBody = constructor.body();
    conBody.invoke("this").arg(okapiUrl(host, port)).arg(tenantIdVar).arg(tokenVar).arg(keepAlive)
      .arg(connTimeout).arg(idleTimeout);
    deprecate(constructor);
  }

  private void addConstructorOkapi3Args() {
    JMethod constructor = constructor();
    JVar okapiUrlVar = constructor.param(String.class, OKAPI_URL);
    JVar tenantIdVar = constructor.param(String.class, TENANT_ID);
    JVar tokenVar = constructor.param(String.class, TOKEN);
    JBlock conBody = constructor.body();
    conBody.invoke("this").arg(okapiUrlVar).arg(tenantIdVar).arg(tokenVar).arg(JExpr.TRUE)
      .arg(JExpr.lit(2000)).arg(JExpr.lit(5000));
  }

  private void addConstructorOkapi4Args() {
    JMethod constructor = constructor();
    JVar okapiUrlVar = constructor.param(String.class, OKAPI_URL);
    JVar tenantIdVar = constructor.param(String.class, TENANT_ID);
    JVar tokenVar = constructor.param(String.class, TOKEN);
    JVar keepAlive = constructor.param(boolean.class, KEEP_ALIVE);
    JBlock conBody = constructor.body();
    conBody.invoke("this").arg(okapiUrlVar).arg(tenantIdVar).arg(tokenVar).arg(keepAlive)
      .arg(JExpr.lit(2000)).arg(JExpr.lit(5000));
  }
  private void addConstructorOkapi6Args(JFieldVar tokenVar, JFieldVar options, JFieldVar httpClient) {
    /* constructor, init the httpClient - allow to pass keep alive option */
    JMethod constructor = constructor();
    JVar okapiUrlVar = constructor.param(String.class, OKAPI_URL);
    JVar tenantIdVar = constructor.param(String.class, TENANT_ID);
    JVar token = constructor.param(String.class, TOKEN);
    JVar keepAlive = constructor.param(boolean.class, KEEP_ALIVE);
    JVar connTimeout = constructor.param(int.class, "connTO");
    JVar idleTimeout = constructor.param(int.class, "idleTO");

    /* populate constructor */
    JBlock conBody = constructor.body();
    conBody.assign(JExpr._this().ref(tenantId), tenantIdVar);
    conBody.assign(JExpr._this().ref(tokenVar), token);
    conBody.assign(JExpr._this().ref(okapiUrl), okapiUrlVar);
    conBody.assign(options, JExpr._new(jcodeModel.ref(io.vertx.ext.web.client.WebClientOptions.class)));
    conBody.invoke(options, "setLogActivity").arg(JExpr.TRUE);
    conBody.invoke(options, "setKeepAlive").arg(keepAlive);
    conBody.invoke(options, "setConnectTimeout").arg(connTimeout);
    conBody.invoke(options, "setIdleTimeout").arg(idleTimeout);

    JExpression client = jcodeModel
        .ref("io.vertx.ext.web.client.WebClient")
        .staticInvoke("create").arg(jcodeModel
        .ref("org.folio.rest.tools.utils.VertxUtils").staticInvoke("getVertxFromContextOrNew")).arg(options);
    conBody.assign(httpClient, client);
  }

  /**
   * Create a method and add a comment to the body of the method saying that it
   * is auto-generated and how.
   * @param mods  Modifiers for this method
   * @param type  Return type for this method
   * @param name  name for this method
   * @return the new method
   */
  private JMethod method(int mods, Class <?> type, String name) {
    JMethod method = jc.method(mods, type, name);
    addCommentAutogenerated(method);
    return method;
  }

  public void generateClassMeta(String className) {

    String mapType = System.getProperty("json.type");
    if ("mongo".equals(mapType)) {
      mappingType = "mongo";
    }

    /* Adding packages here */
    JPackage jp = jcodeModel._package(RTFConsts.CLIENT_GEN_PACKAGE);

    try {
      /* Giving Class Name to Generate */
      this.className = className.substring(RTFConsts.INTERFACE_PACKAGE.length() + 1);
      jc = jp._class(this.className + CLIENT_CLASS_SUFFIX);
      JDocComment com = jc.javadoc();
      com.add("Auto-generated code - based on class " + className);

      /* class variable tenant id */
      tenantId = jc.field(JMod.PRIVATE, String.class, TENANT_ID);

      JFieldVar tokenVar = jc.field(JMod.PRIVATE, String.class, TOKEN);

      okapiUrl = jc.field(JMod.PRIVATE, String.class, OKAPI_URL);

      /* class variable to http options */
      JFieldVar options = jc.field(JMod.PRIVATE, WebClientOptions.class, "options");

      /* class variable to http client */
      JFieldVar httpClient = jc.field(JMod.PRIVATE, WebClient.class, "httpClient");

      addConstructorOkapi6Args(tokenVar, options, httpClient);
      addConstructorOkapi4Args();
      addConstructorOkapi3Args();

      addConstructor7Args();
      addConstructor5Args();
      addConstructor4Args();
      addConstructor0Args();

    } catch (Exception e) {
      log.error(e.getMessage(), e);
    }

  }

  public void generateCloseClient(){
    JMethod jmCreate = method(JMod.PUBLIC, void.class, "close");
    jmCreate.javadoc().add("Close the client. Closing will close down any "
        + "pooled connections. Clients should always be closed after use.");
    JBlock body = jmCreate.body();

    body.directStatement("httpClient.close();");
  }

  public static void makeCleanDir(String dirPath) throws IOException {
    File dir = new File(dirPath);
    if (dir.exists()) {
      FileUtils.cleanDirectory(dir);
    } else {
      dir.mkdirs();
    }
  }

  public void generateMethodMeta(String methodName, JsonObject params, String url,
      String httpVerb, JsonArray contentType, JsonArray accepts){

    /* Adding method to the Class which is public and returns void */

    JMethod jmCreate = method(JMod.PUBLIC, void.class, methodName);
    JBlock body = jmCreate.body();

    /* create the query parameter string builder */
    JVar queryParams = body.decl(jcodeModel._ref(StringBuilder.class), "queryParams",
            JExpr._new(jcodeModel.ref(StringBuilder.class)).arg("?"));


    ////////////////////////---- Handle place holders in the url  ----//////////////////
    /* create request */
    /* Handle place holders in the URL
      * replace {varName} with "+varName+" so that it will be replaced
      * in the url at runtime with the correct values */
    Matcher m = Pattern.compile("\\{.*?\\}").matcher(url);
    while(m.find()){
      String varName = m.group().replace("{","").replace("}", "");
      url = url.replace("{"+varName+"}", "\"+"+varName+"+\"");
    }

    url = "\""+url.substring(1)+"\"+queryParams.toString()";

    /* Adding java doc for method */
    jmCreate.javadoc().add("Service endpoint " + url);


    /* iterate on function params and add the relevant ones
     * --> functionSpecificQueryParamsPrimitives is populated by query parameters that are primitives
     * --> functionSpecificHeaderParams (used later on) is populated by header params
     * --> functionSpecificQueryParamsEnums is populated by query parameters that are enums */
    Iterator<Entry<String, Object>> paramList = params.iterator();

    boolean [] bodyContentExists = new boolean[]{false};
    paramList.forEachRemaining(entry -> {
      String valueName = ((JsonObject) entry.getValue()).getString("value");
      String valueType = ((JsonObject) entry.getValue()).getString("type");
      String paramType = ((JsonObject) entry.getValue()).getString("param_type");
      if(handleParams(jmCreate, queryParams, paramType, valueType, valueName)){
        bodyContentExists[0] = true;
      }
    });

    //////////////////////////////////////////////////////////////////////////////////////

    /* create the http client request object */
    final String httpMethodName = httpVerb.substring(httpVerb.lastIndexOf('.') + 1).toUpperCase();
    body.directStatement("io.vertx.ext.web.client.HttpRequest<Buffer> request = httpClient.requestAbs("+
        "io.vertx.core.http.HttpMethod."+ httpMethodName +", okapiUrl+"+url+");");

    /* add headers to request */
    functionSpecificHeaderParams.forEach(body::directStatement);
    //reset for next method usage
    functionSpecificHeaderParams = new ArrayList<>();

    /* add content and accept headers if relevant */
    if(contentType != null){
      String cType = contentType.toString().replace("\"", "").replace("[", "").replace("]", "");
      if(contentType.contains("multipart/form-data")){
        body.directStatement("request.putHeader(\"Content-type\", \""+cType+"; boundary=--BOUNDARY\");");
      }
      else{
        body.directStatement("request.putHeader(\"Content-type\", \""+cType+"\");");
      }
    }
    if(accepts != null){
      String aType = accepts.toString().replace("\"", "").replace("[", "").replace("]", "");
      //replace any/any with */* to allow declaring accpet */* which causes compilation issues
      //when declared in raml. so declare any/any in raml instead and replaced here
      aType = aType.replaceAll("any/any", "");
      body.directStatement("request.putHeader(\"Accept\", \""+aType+"\");");
    }

    /* push tenant id into x-okapi-tenant and authorization headers for now */
    JConditional ifClause = body._if(tenantId.ne(JExpr._null()));
    ifClause._then().directStatement("request.putHeader(\"X-Okapi-Token\", token);");
    ifClause._then().directStatement("request.putHeader(\""+OKAPI_HEADER_TENANT+"\", tenantId);");

    JConditional ifClause2 = body._if(this.okapiUrl.ne(JExpr._null()));
    ifClause2._then().directStatement("request.putHeader(\"X-Okapi-Url\", okapiUrl);");

    /* add response handler to each function */
    JClass handler = jcodeModel.ref(HttpResponse.class).narrow(Buffer.class);
    handler = jcodeModel.ref(AsyncResult.class).narrow(handler);
    handler = jcodeModel.ref(Handler.class).narrow(handler);
    jmCreate.param(handler, "responseHandler");
    jmCreate.param(handler, "responseHandler");

    /* if we need to pass data in the body */
    if(bodyContentExists[0]){
      body.directStatement("request.putHeader(\"Content-Length\", buffer.length()+\"\");");
      //body.directStatement("request.setChunked(true);"); //TODO: consider adding this somehow
      body.directStatement("request.sendBuffer(buffer, responseHandler);");
    } else {
      body.directStatement("request.send(responseHandler);");
    }
  }

  private void addParameter(ParameterDetails details) {
    JBlock b = details.methodBody;
    if (Boolean.TRUE.equals(details.nullCheck)) {
      JConditional ifClause = details.methodBody._if(JExpr.ref(details.valueName).ne(JExpr._null()));
      b = ifClause._then();
    }
    b.invoke(details.queryParams, APPEND).arg(JExpr.lit(details.valueName + "="));
    switch (details.op) {
      case ENCODE:
        encodeParameter(b, details);
        break;
      case FORMAT_DATE:
        formatDateParameter(b, details);
        break;
      case PROCESS_LIST:
        processListParameter(b, details);
        break;
      case NONE:
        b.invoke(details.queryParams, APPEND).arg(JExpr.ref(details.valueName));
        break;
    }
    b.invoke(details.queryParams, APPEND).arg(JExpr.lit("&"));
  }

  private void encodeParameter(JBlock b, ParameterDetails details) {
    JExpression expr = jcodeModel.ref(java.net.URLEncoder.class)
      .staticInvoke("encode")
        .arg(JExpr.ref(details.valueName))
        .arg("UTF-8");
    b.invoke(details.queryParams, APPEND).arg(expr);
  }

  private void formatDateParameter(JBlock b, ParameterDetails details) {
    JExpression expr = jcodeModel.ref(java.time.format.DateTimeFormatter.class)
      .staticRef("ISO_LOCAL_DATE_TIME")
      .invoke("format")
        .arg(jcodeModel.ref(java.time.ZonedDateTime.class)
          .staticInvoke("ofInstant")
            .arg(JExpr.ref(details.valueName).invoke("toInstant"))
            .arg(jcodeModel.ref(java.time.ZoneId.class)
              .staticInvoke("of")
                .arg("UTC")));
    b.invoke(details.queryParams, APPEND).arg(expr);
  }

  private void processListParameter(JBlock b, ParameterDetails details) {
    b.directStatement(new StringBuilder("if(")
      .append(details.valueName)
      .append(".getClass().isArray())")
      .append("{queryParams.append(String.join(\"&")
      .append(details.valueName)
      .append("=\",")
      .append(details.valueName)
      .append("));}")
      .toString());
  }

  /**
   * @param name  the name of the class to search for
   * @return Class.forName(name), or Void.class if the class does not exist
   */
  private Class<?> classForName(final String name) {
    try {
      return Class.forName(name);
    } catch (ClassNotFoundException e) {
      log.error(e.getMessage(), e);
      return Void.class;
    }
  }

  /**
   * @param paramType
   * @param valueType
   */
  private boolean handleParams(JMethod method, JVar queryParams, String paramType, String valueType, String valueName) {

    JBlock methodBody = method.body();

    if (AnnotationGrabber.NON_ANNOTATED_PARAM.equals(paramType) /*&& !FILE_UPLOAD_PARAM.equals(valueType)*/) {
      try {
        // this will also validate the json against the pojo created from the schema
        Class<?> entityClazz = Class.forName(valueType);

        if (!valueType.equals("io.vertx.core.Handler") && !valueType.equals("io.vertx.core.Context") &&
            !valueType.equals("java.util.Map") && !valueType.equals("io.vertx.ext.web.RoutingContext")) {

          /* this is a post or put since our only options here are receiving a reader (data in body) or
           * entity - which is also data in body - but we can only have one since a multi part body
           * should be indicated by a multipart objector input stream in the body */
          JExpression jexpr = jcodeModel.ref(io.vertx.core.buffer.Buffer.class).staticInvoke("buffer");
          JVar buffer = methodBody.decl(jcodeModel.ref(io.vertx.core.buffer.Buffer.class), "buffer", jexpr);

          if ("java.io.Reader".equals(valueType)){
            JVar reader = method.param(Reader.class, "reader");
            method._throws(Exception.class);
            JConditional ifClause = methodBody._if(reader.ne(JExpr._null()));
           ifClause._then().directStatement( "buffer.appendString(org.apache.commons.io.IOUtils.toString(reader));" );
          }
          else if("java.io.InputStream".equals(valueType)){
            JVar inputStream = method.param(InputStream.class, "inputStream");
            JVar result = methodBody.decl(jcodeModel.ref(ByteArrayOutputStream.class), "result", JExpr._new(jcodeModel.ref(ByteArrayOutputStream.class)));
            JVar byteA = methodBody.decl(jcodeModel.BYTE.array(), "buffer1", JExpr.newArray(jcodeModel.BYTE, 1024));
            JVar length = methodBody.decl(jcodeModel.INT, "length");
            // http://stackoverflow.com/questions/26037015/how-do-i-force-enclose-a-codemodel-expression-in-brackets
            JWhileLoop whileClause = methodBody._while(JExpr.TRUE);
            whileClause.body().assign(length, inputStream.invoke("read").arg(byteA));
            whileClause.body()._if(length.eq(JExpr.lit(-1)))._then()._break();
            whileClause.body().add(result.invoke("write").arg(byteA).arg(JExpr.lit(0)).arg(length));
            methodBody.add(buffer.invoke("appendBytes").arg(result.invoke("toByteArray")));
            method._throws(IOException.class);
          }
          else if("javax.mail.internet.MimeMultipart".equals(valueType)){
            JVar mimeMultiPart = method.param(MimeMultipart.class, "mimeMultipart");
            method._throws(MessagingException.class);
            method._throws(IOException.class);
            JBlock b1 = methodBody._if(mimeMultiPart.ne(JExpr._null()))._then();
            JVar parts = b1.decl(jcodeModel.INT, "parts", mimeMultiPart.invoke("getCount"));
            JVar sb = b1.decl(jcodeModel._ref(StringBuilder.class), "sb",
                    JExpr._new(jcodeModel.ref(StringBuilder.class)));
            JForLoop forClause = b1._for();
            JVar iVar = forClause.init(jcodeModel._ref(int.class), "i", JExpr.lit(0));
            forClause.test(iVar.lt(parts));
            forClause.update(iVar.incr());
            JBlock fBody = forClause.body();
            JVar bp = fBody.decl(jcodeModel.ref(javax.mail.BodyPart.class), "bp", mimeMultiPart.invoke("getBodyPart").arg(iVar));
            fBody.add(sb.invoke(APPEND).arg("----BOUNDARY\r\n"));
            fBody.add(sb.invoke(APPEND).arg("Content-Disposition: \""));
            fBody.add(sb.invoke(APPEND).arg(bp.invoke("getDisposition")));
            fBody.add(sb.invoke(APPEND).arg("\"; name=\""));
            fBody.add(sb.invoke(APPEND).arg(bp.invoke("getFileName")));
            fBody.add(sb.invoke(APPEND).arg("\"; filename=\")"));
            fBody.add(sb.invoke(APPEND).arg(bp.invoke("getFileName")));
            fBody.add(sb.invoke(APPEND).arg("\"\r\n"));
            fBody.add(sb.invoke(APPEND).arg("Content-Type: application/octet-stream\r\n"));
            fBody.add(sb.invoke(APPEND).arg("Content-Transfer-Encoding: binary\r\n"));
            b1.add(sb.invoke(APPEND).arg("----BOUNDARY\r\n"));
            b1.add(buffer.invoke("appendString").arg(sb.invoke("toString")));
          }
          else{
            String objParamName = entityClazz.getSimpleName();
            JConditional ifClause = methodBody._if(JExpr.ref(objParamName).ne(JExpr._null()));
            JBlock b = ifClause._then();
            if(mappingType.equals("postgres")){
              method._throws(Exception.class);
              b.directStatement("buffer.appendString("
                  + "org.folio.rest.tools.ClientHelpers.pojo2json("+objParamName+"));");
            }else{
              b.directStatement( "buffer.appendString("
                  + "org.folio.rest.tools.utils.JsonUtils.entity2Json("+objParamName+").encode());");
            }
            method.param(entityClazz, entityClazz.getSimpleName());
          }
          return true;
        }
      } catch (Exception e) {
        log.error(e.getMessage(), e);
      }
    }
    else if (AnnotationGrabber.PATH_PARAM.equals(paramType)) {
      method.param(String.class, valueName);
    }
    else if (AnnotationGrabber.HEADER_PARAM.equals(paramType)) {
      method.param(String.class, valueName);
      functionSpecificHeaderParams.add("request.putHeader(\""+valueName+"\", "+valueName+");");
    }
    else if (AnnotationGrabber.QUERY_PARAM.equals(paramType)) {
      // support date, enum, numbers or strings as query parameters
      try {
        if (valueType.contains("String")) {
          method.param(String.class, valueName);
          method._throws(UnsupportedEncodingException.class);
          addParameter(new ParameterDetails(methodBody, queryParams, valueName)
            .withOp(ParameterOp.ENCODE));
        } else if (valueType.contains("Date")) {
          method.param(Date.class, valueName);
          addParameter(new ParameterDetails(methodBody, queryParams, valueName)
            .withOp(ParameterOp.FORMAT_DATE));
        } else if (valueType.contains("int")) {
          method.param(int.class, valueName);
          addParameter(new ParameterDetails(methodBody, queryParams, valueName)
            .nullCheck(false));
        } else if (valueType.contains("boolean")) {
          method.param(boolean.class, valueName);
          addParameter(new ParameterDetails(methodBody, queryParams, valueName)
            .nullCheck(false));
        } else if (valueType.contains("BigDecimal")) {
          method.param(BigDecimal.class, valueName);
          addParameter(new ParameterDetails(methodBody, queryParams, valueName));
        } else if (valueType.contains("Number")) {
          method.param(Number.class, valueName);
          addParameter(new ParameterDetails(methodBody, queryParams, valueName));
        } else if (valueType.contains("Integer")) {
          method.param(Integer.class, valueName);
          addParameter(new ParameterDetails(methodBody, queryParams, valueName));
        } else if (valueType.contains("Boolean")) {
          method.param(Boolean.class, valueName);
          addParameter(new ParameterDetails(methodBody, queryParams, valueName));
        } else if (valueType.contains("List")) {
          method.param(String[].class, valueName);
          addParameter(new ParameterDetails(methodBody, queryParams, valueName)
            .withOp(ParameterOp.PROCESS_LIST));
        } else {
          // enum object type
          Class<?> enumClazz = classForName(valueType);
          if (enumClazz.isEnum()) {
            method.param(enumClazz, valueName);
            addParameter(new ParameterDetails(methodBody, queryParams, valueName));
          }
        }
      } catch (Exception e) {
        log.error(e.getMessage(), e);
      }
    }
    return false;
  }

  public void generateClass(JsonObject classSpecificMapping) throws IOException {
    String genPath = System.getProperty("project.basedir") + PATH_TO_GENERATE_TO;
    jcodeModel.build(new File(genPath));
  }

  private class ParameterDetails {
    final JBlock methodBody;
    final JVar queryParams;
    final String valueName;
    ParameterOp op = ParameterOp.NONE;
    Boolean nullCheck = true;
    public ParameterDetails(JBlock methodBody, JVar queryParams, String valueName) {
      this.methodBody = methodBody;
      this.queryParams = queryParams;
      this.valueName = valueName;
    }
    public ParameterDetails withOp(ParameterOp op) {
      this.op = op;
      return this;
    }
    public ParameterDetails nullCheck(Boolean nullCheck) {
      this.nullCheck = nullCheck;
      return this;
    }
  }

  private enum ParameterOp {
    ENCODE, FORMAT_DATE, PROCESS_LIST, NONE
  }

}
