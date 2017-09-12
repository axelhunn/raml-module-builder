package org.folio.rest.impl;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import javax.ws.rs.core.Response;

import org.apache.commons.io.IOUtils;
import org.folio.rest.annotations.Validate;
import org.folio.rest.jaxrs.model.TenantAttributes;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.persist.ddlgen.SchemaMaker;
import org.folio.rest.persist.ddlgen.Table;
import org.folio.rest.persist.ddlgen.View;
import org.folio.rest.tools.ClientGenerator;
import org.folio.rest.tools.messages.Messages;
import org.folio.rest.tools.utils.ObjectMapperTool;
import org.folio.rest.tools.utils.OutStream;
import org.folio.rest.tools.utils.TenantTool;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;


/**
 * @author shale
 *
 */
public class TenantAPI implements org.folio.rest.jaxrs.resource.TenantResource {

  public static final String       TABLE_JSON = "templates/db_scripts/create_table.json";
  public static final String       VIEW_JSON = "templates/db_scripts/create_view.json";

  public static final String       UPDATE_TENANT_TEMPLATE = "template_update_tenant.sql";
  public static final String       DELETE_TENANT_TEMPLATE = "template_delete_tenant.sql";
  public static final String       AUDIT_TENANT_TEMPLATE  = "template_audit.sql";
  public static final String       UPDATE_AUDIT_TENANT_TEMPLATE  = "template_update_audit.sql";
  private static final String      TEMPLATE_TENANT_PLACEHOLDER   = "myuniversity";
  private static final String      TEMPLATE_MODULE_PLACEHOLDER   = "mymodule";
  private static final String      UPGRADE_FROM_VERSION          = "module_from";
  private static final String      UPGRADE_TO_VERSION            = "module_to";


  private static final Logger       log               = LoggerFactory.getLogger(TenantAPI.class);
  private final Messages            messages          = Messages.getInstance();


  @Validate
  @Override
  public void deleteTenant(Map<String, String> headers,
      Handler<AsyncResult<Response>> handlers, Context context) throws Exception {

    context.runOnContext(v -> {
      try {

        System.out.println("sending... deleteTenant");
        String tenantId = TenantTool.calculateTenantId( headers.get(ClientGenerator.OKAPI_HEADER_TENANT) );

        tenantExists(context, tenantId,
          h -> {
            boolean exists = false;
            if(h.succeeded()){
              exists = h.result();
              if(!exists){
                handlers.handle(io.vertx.core.Future.succeededFuture(DeleteTenantResponse.
                  withPlainInternalServerError("Tenant does not exist: " + tenantId)));
                log.error("Can not delete. Tenant does not exist: " + tenantId);
                return;
              }
              else{
                log.info("Deleting tenant " + tenantId);
              }
            }
            else{
              handlers.handle(io.vertx.core.Future.failedFuture(h.cause().getMessage()));
              log.error(h.cause().getMessage(), h.cause());
              return;
            }

            String sqlFile = null;
            try {
              sqlFile = IOUtils.toString(
                TenantAPI.class.getClassLoader().getResourceAsStream(DELETE_TENANT_TEMPLATE));
            } catch (Exception e1) {
              handlers.handle(io.vertx.core.Future.failedFuture(e1.getMessage()));
              log.error(e1.getMessage(), e1);
              return;
            }

            String sql2run = sqlFile.replaceAll(TEMPLATE_TENANT_PLACEHOLDER, tenantId);
            sql2run = sql2run.replaceAll(TEMPLATE_MODULE_PLACEHOLDER, PostgresClient.getModuleName());
            /* connect as user in postgres-conf.json file (super user) - so that all commands will be available */
            PostgresClient.getInstance(context.owner()).runSQLFile(sql2run, false,
                reply -> {
                  try {
                    String res = "";
                    if(reply.succeeded()){
                      res = new JsonArray(reply.result()).encodePrettily();
                      if(reply.result().size() > 0){
                        handlers.handle(io.vertx.core.Future.succeededFuture(DeleteTenantResponse.withPlainBadRequest(res)));
                      }
                      else {
                        OutStream os = new OutStream();
                        os.setData(res);
                        handlers.handle(io.vertx.core.Future.succeededFuture(DeleteTenantResponse.withNoContent()));
                      }
                    }
                    else {
                      log.error(reply.cause().getMessage(), reply.cause());
                      handlers.handle(io.vertx.core.Future.succeededFuture(DeleteTenantResponse
                        .withPlainInternalServerError(reply.cause().getMessage())));
                    }
                  } catch (Exception e) {
                    log.error(e.getMessage(), e);
                    handlers.handle(io.vertx.core.Future.succeededFuture(DeleteTenantResponse
                      .withPlainInternalServerError(e.getMessage())));
                  }
                });
          });
      } catch (Exception e) {
        log.error(e.getMessage(), e);
        handlers.handle(io.vertx.core.Future.succeededFuture(DeleteTenantResponse
          .withPlainInternalServerError(e.getMessage())));
      }
    });
  }

  void tenantExists(Context context, String tenantId, Handler<AsyncResult<Boolean>> handler){
    /* connect as user in postgres-conf.json file (super user) - so that all commands will be available */
    PostgresClient.getInstance(context.owner()).select(
      "SELECT EXISTS(SELECT 1 FROM pg_namespace WHERE nspname = '"+ PostgresClient.convertToPsqlStandard(tenantId) +"');",
        reply -> {
          try {
            if(reply.succeeded()){
              handler.handle(io.vertx.core.Future.succeededFuture(reply.result().getResults().get(0).getBoolean(0)));
            }
            else {
              log.error(reply.cause().getMessage(), reply.cause());
              handler.handle(io.vertx.core.Future.failedFuture(reply.cause().getMessage()));
            }
          } catch (Exception e) {
            log.error(e.getMessage(), e);
            handler.handle(io.vertx.core.Future.failedFuture(e.getMessage()));
          }
    });
  }

  @Validate
  @Override
  public void getTenant(Map<String, String> headers, Handler<AsyncResult<Response>> handlers,
      Context context) throws Exception {

    context.runOnContext(v -> {
      try {

        System.out.println("sending... postTenant");
        String tenantId = TenantTool.calculateTenantId( headers.get(ClientGenerator.OKAPI_HEADER_TENANT) );

        tenantExists(context, tenantId, res -> {
          boolean exists = false;
          if(res.succeeded()){
            exists = res.result();
            handlers.handle(io.vertx.core.Future.succeededFuture(GetTenantResponse.withPlainOK(String.valueOf(
              exists))));
          }
          else{
            log.error(res.cause().getMessage(), res.cause());
            handlers.handle(io.vertx.core.Future.succeededFuture(GetTenantResponse
              .withPlainInternalServerError(res.cause().getMessage())));
          }
        });
      } catch (Exception e) {
        log.error(e.getMessage(), e);
        handlers.handle(io.vertx.core.Future.succeededFuture(GetTenantResponse
          .withPlainInternalServerError(e.getMessage())));
      }
    });
  }


  @Validate
  @Override
  public void postTenant(TenantAttributes entity, Map<String, String> headers,
      Handler<AsyncResult<Response>> handlers, Context context) throws Exception {

    /**
     * http://host:port/tenant
     */

    context.runOnContext(v -> {
      System.out.println("sending... postTenant");
      String tenantId = TenantTool.calculateTenantId(headers.get(ClientGenerator.OKAPI_HEADER_TENANT));

      try {
        boolean isUpdateMode[] = new boolean[]{false};
        //body is optional so that the TenantAttributes
        if(entity != null){
          log.debug("upgrade from " + entity.getModuleFrom() + " to " + entity.getModuleTo());
          try {
            if(entity.getModuleFrom() != null){
              isUpdateMode[0] = true;
            }
          } catch (Exception e) {
            log.error(e.getMessage(), e);
            handlers.handle(io.vertx.core.Future.succeededFuture(PostTenantResponse.
              withPlainBadRequest(e.getMessage())));
            return;
          }
        }

        tenantExists(context, tenantId,
          h -> {
            try {
              boolean tenantExists = false;
              if(h.succeeded()){
                tenantExists = h.result();
                if(tenantExists && !isUpdateMode[0]){
                  //tenant exists and a create tenant request was made, then this should do nothing
                  //if tenant exists then only update tenant request is acceptable
                  handlers.handle(io.vertx.core.Future.succeededFuture(PostTenantResponse
                    .withNoContent()));
                  log.warn("Tenant already exists: " + tenantId);
                  return;
                }
                else if(!tenantExists && isUpdateMode[0]){
                  //update requested for a non-existant tenant
                  log.error("Can not update non-existant tenant " + tenantId);
                  handlers.handle(io.vertx.core.Future.succeededFuture(
                    PostTenantResponse.withPlainBadRequest(
                      "Update tenant requested for tenant " + tenantId + ", but tenant doe not exist")));
                  return;
                }
                else{
                  log.info("adding/updating tenant " + tenantId);
                }
              }
              else{
                handlers.handle(io.vertx.core.Future.failedFuture(h.cause().getMessage()));
                log.error(h.cause().getMessage(), h.cause());
                return;
              }

              InputStream tableInput = TenantAPI.class.getClassLoader().getResourceAsStream(
                TABLE_JSON);
              InputStream viewInput = TenantAPI.class.getClassLoader().getResourceAsStream(
                VIEW_JSON);
              if(tableInput == null && viewInput == null) {
                handlers.handle(io.vertx.core.Future.succeededFuture(PostTenantResponse
                  .withNoContent()));
                log.info("Could not find templates/db_scripts/create_table.json , "
                    + "templates/db_scripts/create_view.json, RMB will not run any scripts for " + tenantId);
                return;
              }
              String tableInputStr = IOUtils.toString(tableInput, "UTF8");
              String viewInputStr = IOUtils.toString(viewInput, "UTF8");

              List<Table> tables = (List<Table>)ObjectMapperTool.getMapper().readValue(
                tableInputStr, ObjectMapperTool.getMapper().getTypeFactory().constructCollectionType(List.class, Table.class));

              List<View> views = (List<View>)ObjectMapperTool.getMapper().readValue(
                viewInputStr, ObjectMapperTool.getMapper().getTypeFactory().constructCollectionType(List.class, View.class));

              String op = "create";
              double version = 0.0;
              if(isUpdateMode[0]){
                op = "update";
                version = Double.parseDouble(entity.getModuleFrom());
              }
              SchemaMaker sMaker = new SchemaMaker(tenantId, PostgresClient.getModuleName(), op, version);
              sMaker.setTables(tables);
              sMaker.setViews(views);
              String sqlFile = sMaker.generateDDL();

              /* connect as user in postgres-conf.json file (super user) - so that all commands will be available */
              PostgresClient.getInstance(context.owner()).runSQLFile(sqlFile, false,
                reply -> {
                  try {
                    StringBuffer res = new StringBuffer();
                    if (reply.succeeded()) {
                      boolean failuresExist = false;
                      if(reply.result().size() > 0){
                        failuresExist = true;
                      }
                      res.append(new JsonArray(reply.result()).encodePrettily());
                      OutStream os = new OutStream();
                      if(failuresExist){
                        handlers.handle(io.vertx.core.Future.succeededFuture(
                          PostTenantResponse.withPlainBadRequest(res.toString())));
                      }
                      else{
                        os.setData(res);
                        handlers.handle(io.vertx.core.Future.succeededFuture(PostTenantResponse.withJsonCreated(os)));
                      }
                    } else {
                      log.error(reply.cause().getMessage(), reply.cause());
                      handlers.handle(io.vertx.core.Future.succeededFuture(PostTenantResponse.
                        withPlainInternalServerError(reply.cause().getMessage())));
                    }
                  } catch (Exception e) {
                    log.error(e.getMessage(), e);
                    handlers.handle(io.vertx.core.Future.succeededFuture(PostTenantResponse.
                      withPlainInternalServerError(e.getMessage())));
                  }
                });
            } catch (Exception e) {
              log.error(e.getMessage(), e);
              handlers.handle(io.vertx.core.Future.succeededFuture(PostTenantResponse.
                withPlainInternalServerError(e.getMessage())));
            }
          });
      } catch (Exception e) {
        log.error(e.getMessage(), e);
        handlers.handle(io.vertx.core.Future.succeededFuture(PostTenantResponse.
          withPlainInternalServerError(e.getMessage())));
      }
    });
  }

  /**
   * @param jar
   * @return
   */
  private void validateJson(JsonObject jar) throws Exception {
    System.out.println("jobj =................................. " + jar);
    if(!jar.containsKey(UPGRADE_FROM_VERSION)){
      throw new Exception(UPGRADE_FROM_VERSION + " entry does not exist in post tenant request body");
    }
  }

  private void toMap(TenantAttributes jar, List<String> placeHolders, List<String> values){
    try {
      placeHolders.add(UPGRADE_FROM_VERSION);
      placeHolders.add(UPGRADE_TO_VERSION);
      values.add(jar.getModuleFrom());
      values.add(jar.getModuleTo());
    } catch (Exception e) {
      log.warn("Unable to parse body", e);
    }
  }

}
