package org.rhythm

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

import javax.swing.text.html.HTMLDocument
import javax.swing.text.html.HTMLEditorKit

import org.cyberneko.html.parsers.SAXParser
import org.vertx.groovy.core.Vertx
import org.vertx.groovy.core.http.HttpServer


class RhythmUI {
	private static int SERVER_PORT = 8686;
	private static final String SERVER_ACCEPT_LISTEN= "localhost";
	private static final String CHROME_EXE =  "start chrome ";
	private static final String COMMAND_LINE_CALL = "cmd /c "
	private static final String CHROME_FLAG = "--app=";
	private static final String HEAD_TAG = "head";
	private static final String HTML_TAG = "html";
	private static final String SCRIPT_TAG = 'script';
	private static final String SCRIPT_TYPE_PROPERTY = "text/javascript";
	private static final String RHYTHMJS_LOCATION ="web/js/RhythmJS.js"
	private static final String CLOSE_ON_FILE_FAIL_PAGE_LOCATION ="web/closeOnFileFail.html"
	private static final String RHYTHM_WEBSOCKET_PATH = "/rhythm";
	private static final String COMMAND_PROMPT = "cmd";
	private static final String COMMAND_PROMPT_FLAG = "/C";
	private static final String LOCAL_HOST = "http://localhost:";
	private static final String USER_DIRECTORY = "user.dir"
	
	private static Vertx vertx = Vertx.newVertx();
	private static HttpServer server = null;
	private static HTMLEditorKit htmlKit = new HTMLEditorKit();
	private static Reader stringReader = null;
	private static HTMLDocument page = null;
	private static Thread rhythmThread;
	private static Process chromeProcess;
	private static ReentrantLock rhythmServerThreadControlLock = new ReentrantLock();
	private static ReentrantLock rhythmAppThreadControlLock = new ReentrantLock();
	private static ReentrantLock mainThreadControlLock = new ReentrantLock();
	private static int appCount = 0;
	
	private static boolean rhythmInitialized = false;
	private static boolean closeOnFileFail = false;
	
	private static methods = [:] as ConcurrentHashMap;
	
	/**
	 * Singleton for Rhythms vert.x server
	 * @return
	 */
	public static HttpServer getServerInstance(){
		if(server == null){
			server = vertx.createHttpServer();
			
			server.requestHandler({ request ->
				def file = "";
				
				if(request.path.endsWith("favicon.ico")){
					//TODO: set file to Rhythm Logo
					request.response.end();
					return;
				}
				
				if(!request.path.contains("..")){
					file = request.path;
				}
				
				vertx.fileSystem.readFile(constructFilePath(file)) { ar ->
					if (ar.succeeded) {
						try {
							def root = new XmlParser(new org.cyberneko.html.parsers.SAXParser()).parseText(ar.result.toString());
							
							def rhythmScript = new Node(null, SCRIPT_TAG, [type : SCRIPT_TYPE_PROPERTY], new File(RHYTHMJS_LOCATION).text);
							
							def head = root."**".findAll {
								try{
									return it.name().equalsIgnoreCase(HEAD_TAG);
								} catch(Exception e) {
									return false;
								}
							}[0];
						
							if(head){
								head.children().add(0, rhythmScript);
							} else {
								def html = root."**".findAll {
									try{
										return it.name().equalsIgnoreCase(HTML_TAG);
									} catch(Exception e) {
										return false;
									}
								}[0];
							
								def newHead = new Node(null, HEAD_TAG);
							
								html.children().add(0, newHead);
								newHead.children().add(0, rhythmScript)
							}
							
							
							def writer = new StringWriter();
							new XmlNodePrinter(new PrintWriter(writer)).print(root);
							request.response.end(writer.toString());

						} catch (Exception e) {
							//TODO: Fail
							e.printStackTrace();
							return;
						}
					} else {
						//Prevents deadlock if first navigation fails
						if(!rhythmInitialized){
							synchronized(mainThreadControlLock){
								mainThreadControlLock.notify();
							}
						}
					
						System.err.println("Failed to find specified file: " + constructFilePath(file));
						if(closeOnFileFail){
							try{
								request.response.end(new File(CLOSE_ON_FILE_FAIL_PAGE_LOCATION).text);
							} catch(Exception e){
								e.printStackTrace();
								System.err.println("The Rhythm server was aborted due to the closeOnFileFail property. \
									However, there was an error closing the application, please close it manually.");
							}
							stop();
							return;
						} else{
							System.err.println("The Rhythm application and server will no longer be synced.");
							request.response.end("<html>Failed to find specified file: " + constructFilePath(file) +
								"<br> The Rhythm application and server will no longer be synced.<html>");
							return;
						}
					}
				};
			});
		
			server.websocketHandler({ ws ->
				if (ws.path == RHYTHM_WEBSOCKET_PATH) {
					
					synchronized(mainThreadControlLock){
						mainThreadControlLock.notify();
					}
					
					ws.dataHandler{ buffer ->
						 def methodJson = new JsonSlurper().parseText(buffer.toString());
						 def response = [:];
						 def method = methods[methodJson.method.toString()];
						 def methodId = methodJson.methodId.toString();
						 if(method){
							 response = ["results" : method(methodJson.parameters as String[])];
						 }
						 response.methodId = methodId;
						 ws.writeTextFrame(new JsonBuilder(response).toString());
					}
					
					ws.endHandler{
						appCount--;
						stop();
					}
					
				} else {
					ws.reject()
				}
			});
		
			server.listen(SERVER_PORT, SERVER_ACCEPT_LISTEN){ started ->
				if(!rhythmInitialized){
					synchronized(rhythmServerThreadControlLock){
						rhythmServerThreadControlLock.notifyAll();
					}
				}
				
				if(!started.succeeded){

				}
			};
		}
		return server;
	}
	
	private static boolean startChromeApp(String file){
		appCount++
		try {
			chromeProcess = (COMMAND_LINE_CALL + CHROME_EXE + CHROME_FLAG + constructWebPath(file)).execute();
		} catch (Exception e) {
			System.err.println("Rhythm's app chrome is not working:");
			e.printStackTrace();
			return false;
		}
		return true;
	}

	/**
	 * Starts Rhythm's UI thread and starts Chrome with --app flags
	 * This will temporarily halt the calling thread until the server and browser have both launched.
	 * @param file The initial page to navigate to within Chrome
	 * @return The Thread started by executing Chrome
	 */
	public static Thread start(String file) {
		// start chrome with flag and page
		synchronized(mainThreadControlLock){
			
			rhythmThread = Thread.start{
				server = getServerInstance();
					
				if(!rhythmInitialized){
					synchronized(rhythmServerThreadControlLock){
						rhythmServerThreadControlLock.wait();
					}
				}
				
				def chromeStarted = startChromeApp(file);
				if(!chromeStarted){
					//TODO: Notify user that Rhythm App Failed to start, close server
				}
				
				synchronized(rhythmAppThreadControlLock){
					rhythmAppThreadControlLock.wait();
				}
			}
			
			mainThreadControlLock.wait();
		}
		
		rhythmInitialized= true;
		
		return rhythmThread;
	}
	
	/**
	 * This stops the RhythmUI thread through notification
	 */
	public static void stop(){
		synchronized(rhythmAppThreadControlLock){
			if(appCount == 0){
				rhythmAppThreadControlLock.notifyAll();
			}
		}
	}
	
	/**
	 * Maps a closure to be triggered by a specific name
	 * @param name Name to trigger closure
	 * @param function Closure to trigger
	 */
	public static void on(String name, Closure function){
		methods.putAt(name, function);
	}
	
	/**
	 * Maps a closure to be triggered on a specific object by name
	 * @param object The object of the method
	 * @param method The name of the method ont he object and name to trigger method
	 */
	public static void on(Object object, String method){
		methods.putAt(method, object.&"$method");
	}
	
	/**
	 * Maps a named method to be triggered on a specific object by a reference name
	 * @param object The object of the method
	 * @param method The name of the method to trigger on the object
	 * @param name The name to trigger the method
	 */
	public static void on(Object object, String method, String name){
		on(name, object.&"$method");
	}
	
	/**
	 * Creates the absolute file path based on the file for the OS
	 * @param file The file to get the absolute file path for
	 * @return The absolute file path string to the file
	 */
	public static String constructFilePath(String file){
		return getLocalPath() + file.replace("/",  "\\") ;
	}
	
	/**
	 * Creates the URL path to navigate to a specific file
	 * @param file The file to get the URL path to
	 * @return The URL string to the file
	 */
	public static String constructWebPath(String file){
		return LOCAL_HOST + SERVER_PORT + "/" + file + "\"";
	}
	
	/**
	 * Gets the local path to the current directory
	 * @return The user's current directory
	 */
	public static String getLocalPath(){
		return System.getProperty(USER_DIRECTORY);
	}
	
	/**
	 * Sets whether or not RhythmUI stops if a failed page navigation happens. Defaulted to false
	 * @param closeOnFileFail Whether or not to close if a file navigation fails
	 */
	public static void setCloseOnFileFail(boolean closeOnFileFail){
		this.closeOnFileFail = closeOnFileFail;
	}
	
	public static void main(String[] args) throws InterruptedException{
		def ui = new RhythmUI();
		ui.on(RhythmUI.class, "javaTest");
		ui.start("ui/test.html");
	}
	
	public static Map javaTest(String test, String test2){
		println("Java Parameter 1: " + test + ", Java Parameter 2: " + test2);
		return ["results" : "finished java"];
	}
	
}
