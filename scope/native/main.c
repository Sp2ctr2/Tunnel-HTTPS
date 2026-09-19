/* Native Linux GTK/WebKitGTK host. No server, no remote content, no source execution.
 * Compile with native/build.sh. The complete HTML and repository snapshot are a GResource.
 */
#include <gtk/gtk.h>
#include <webkit2/webkit2.h>
#include <jsc/jsc.h>
#include <stdio.h>
#include <string.h>
static GtkWidget *window;
static WebKitWebView *view;
static int exit_status = 0;
static gboolean test_mode = FALSE;
static const char *test_output = NULL;
static guint attempts = 0;
static gboolean test_launched=FALSE;
static void show_error(const char *text) {
    GtkWidget *d=gtk_message_dialog_new(GTK_WINDOW(window),GTK_DIALOG_MODAL,GTK_MESSAGE_ERROR,GTK_BUTTONS_CLOSE,"%s",text);
    gtk_dialog_run(GTK_DIALOG(d));gtk_widget_destroy(d);
}
static gboolean download_destination(WebKitDownload *download, const gchar *suggested, gpointer unused) {
    (void)unused;
    GtkWidget *dialog=gtk_file_chooser_dialog_new("Save — Tunnel Scope",GTK_WINDOW(window),GTK_FILE_CHOOSER_ACTION_SAVE,"Cancel",GTK_RESPONSE_CANCEL,"Save",GTK_RESPONSE_ACCEPT,NULL);
    gtk_file_chooser_set_do_overwrite_confirmation(GTK_FILE_CHOOSER(dialog),TRUE);
    gchar *safe=g_path_get_basename(suggested?suggested:"scope-export");
    gtk_file_chooser_set_current_name(GTK_FILE_CHOOSER(dialog),safe);g_free(safe);
    if(gtk_dialog_run(GTK_DIALOG(dialog))==GTK_RESPONSE_ACCEPT){gchar *filename=gtk_file_chooser_get_filename(GTK_FILE_CHOOSER(dialog));gchar *uri=g_filename_to_uri(filename,NULL,NULL);webkit_download_set_allow_overwrite(download,TRUE);webkit_download_set_destination(download,uri);g_free(uri);g_free(filename);}else webkit_download_cancel(download);
    gtk_widget_destroy(dialog);return TRUE;
}
static void download_started(WebKitWebContext *context,WebKitDownload *download,gpointer unused){(void)context;(void)unused;g_signal_connect(download,"decide-destination",G_CALLBACK(download_destination),NULL);}
static gboolean navigation_policy(WebKitWebView *wv,WebKitPolicyDecision *decision,WebKitPolicyDecisionType type,gpointer unused){
    (void)wv;(void)unused;
    if(type==WEBKIT_POLICY_DECISION_TYPE_NAVIGATION_ACTION||type==WEBKIT_POLICY_DECISION_TYPE_NEW_WINDOW_ACTION){
        WebKitNavigationPolicyDecision *nav=WEBKIT_NAVIGATION_POLICY_DECISION(decision);
        WebKitNavigationAction *a=webkit_navigation_policy_decision_get_navigation_action(nav);
        WebKitURIRequest *request=webkit_navigation_action_get_request(a);const gchar *uri=webkit_uri_request_get_uri(request);
        if(uri&&(g_str_has_prefix(uri,"https://")||g_str_has_prefix(uri,"http://"))){
            /* Only explicit user-clicked links leave the local viewer. */
            if(webkit_navigation_action_is_user_gesture(a))gtk_show_uri_on_window(GTK_WINDOW(window),uri,GDK_CURRENT_TIME,NULL);
            webkit_policy_decision_ignore(decision);return TRUE;
        }
    }return FALSE;
}
static void test_snapshot_done(GObject *source,GAsyncResult *result,gpointer user){
    (void)user;GError *error=NULL;cairo_surface_t *surface=webkit_web_view_get_snapshot_finish(WEBKIT_WEB_VIEW(source),result,&error);
    if(surface){gchar *name=g_strconcat(test_output,".png",NULL);cairo_surface_write_to_png(surface,name);cairo_surface_destroy(surface);g_free(name);}else{g_printerr("Snapshot error: %s\n",error?error->message:"unknown");g_clear_error(&error);}
    gtk_main_quit();
}
static void test_result_done(GObject *source,GAsyncResult *result,gpointer user){
    (void)user;GError *error=NULL;JSCValue *value=webkit_web_view_evaluate_javascript_finish(WEBKIT_WEB_VIEW(source),result,&error);
    if(!value){g_printerr("Self-test evaluate failed: %s\n",error?error->message:"unknown");g_clear_error(&error);exit_status=2;gtk_main_quit();return;}
    char *text=jsc_value_to_string(value);g_print("%s\n",text);if(test_output)g_file_set_contents(test_output,text,-1,NULL);
    if(!strstr(text,"\"ready\":true")||!strstr(text,"\"textFiles\":261")||!strstr(text,"\"sourceMatch\":true")||!strstr(text,"\"fallback\":false"))exit_status=3;
    g_free(text);g_object_unref(value);
    webkit_web_view_get_snapshot(view,WEBKIT_SNAPSHOT_REGION_VISIBLE,WEBKIT_SNAPSHOT_OPTIONS_NONE,NULL,test_snapshot_done,NULL);
}
static gboolean test_finish(gpointer user){(void)user;const char *js="JSON.stringify({ready:!!window.__scope,metrics:window.__scope?.data.metrics,fallback:window.__scope?.view.fallback,textures:window.__scope?.view.activeTextures,sourceMatch:window.__scope?.sourceText?.includes('class DnsCache'),selected:window.__scope?.selected.path,renderer:window.__scope?.view.renderer?.getContext().getParameter(7938)})";webkit_web_view_evaluate_javascript(view,js,-1,NULL,NULL,NULL,test_result_done,NULL);return G_SOURCE_REMOVE;}
static void test_ready_done(GObject *source,GAsyncResult *result,gpointer user){
    (void)user;GError *error=NULL;JSCValue *value=webkit_web_view_evaluate_javascript_finish(WEBKIT_WEB_VIEW(source),result,&error);
    gboolean ready=value&&jsc_value_to_boolean(value);if(value)g_object_unref(value);g_clear_error(&error);
    if(ready&&!test_launched){test_launched=TRUE;webkit_web_view_evaluate_javascript(view,"__scope.focusPage('app/src/main/java/com/tunnelvpn/app/DnsCache.kt',31);__scope.openPath('app/src/main/java/com/tunnelvpn/app/DnsCache.kt',29)",-1,NULL,NULL,NULL,NULL,NULL);g_timeout_add_seconds(5,test_finish,NULL);}
    else if(attempts++>25){g_printerr("Viewer initialization timed out\n");exit_status=4;gtk_main_quit();}
}
static gboolean test_ready(gpointer user){(void)user;if(test_launched)return G_SOURCE_REMOVE;webkit_web_view_evaluate_javascript(view,"!!window.__scope",-1,NULL,NULL,NULL,test_ready_done,NULL);return attempts>25?G_SOURCE_REMOVE:G_SOURCE_CONTINUE;}
int main(int argc,char **argv){
    for(int i=1;i<argc;i++)if(strcmp(argv[i],"--self-test")==0&&i+1<argc){test_mode=TRUE;test_output=argv[++i];}
    gtk_init(&argc,&argv);window=gtk_window_new(GTK_WINDOW_TOPLEVEL);gtk_window_set_title(GTK_WINDOW(window),"Tunnel Scope — Sp2ctr2 / Tunnel-HTTPS");gtk_window_set_default_size(GTK_WINDOW(window),1500,960);gtk_window_set_position(GTK_WINDOW(window),GTK_WIN_POS_CENTER);
    g_signal_connect(window,"destroy",G_CALLBACK(gtk_main_quit),NULL);
    WebKitWebContext *context=webkit_web_context_new_ephemeral();view=WEBKIT_WEB_VIEW(webkit_web_view_new_with_context(context));g_object_unref(context);
    WebKitSettings *settings=webkit_web_view_get_settings(view);webkit_settings_set_enable_webgl(settings,TRUE);webkit_settings_set_enable_javascript(settings,TRUE);webkit_settings_set_javascript_can_access_clipboard(settings,TRUE);webkit_settings_set_enable_developer_extras(settings,TRUE);webkit_settings_set_enable_write_console_messages_to_stdout(settings,TRUE);
    g_signal_connect(view,"decide-policy",G_CALLBACK(navigation_policy),NULL);g_signal_connect(webkit_web_view_get_context(view),"download-started",G_CALLBACK(download_started),NULL);
    gtk_container_add(GTK_CONTAINER(window),GTK_WIDGET(view));gtk_widget_show_all(window);
    GError *error=NULL;GBytes *bytes=g_resources_lookup_data("/tunnel/scope/Tunnel_Scope.html",G_RESOURCE_LOOKUP_FLAGS_NONE,&error);
    if(!bytes){show_error(error?error->message:"Embedded viewer is missing");g_clear_error(&error);return 1;}
    gsize length=0;const gchar *raw=g_bytes_get_data(bytes,&length);gchar *html=g_strndup(raw,length);webkit_web_view_load_html(view,html,"file:///tunnel-scope/");g_free(html);g_bytes_unref(bytes);
    if(test_mode)g_timeout_add_seconds(2,test_ready,NULL);
    gtk_main();return exit_status;
}
