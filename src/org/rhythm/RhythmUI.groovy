package org.rhythm

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper

import javax.swing.text.html.HTMLDocument
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.Element
import javax.swing.text.StyleConstants
import javax.swing.text.html.HTML

import org.cyberneko.html.parsers.SAXParser
import org.vertx.groovy.core.Vertx
import org.vertx.groovy.core.buffer.Buffer
import org.vertx.groovy.core.http.HttpServer


class RhythmUI {
	private static int SERVER_PORT = 8686;
	private static final String SERVER_ACCEPT_LISTEN= "localhost";
	private static final String CHROME_LOCATION =  "\"" + getLocalPath() + File.separator + "chrome" + File.separator + "GoogleChromePortable.exe\" ";
	private static final String CHROME_FLAG = "--app=";
	
	private String filePath = "";
	private static Vertx vertx = Vertx.newVertx();
	private static HttpServer server = null;
	private HTMLEditorKit htmlKit = new HTMLEditorKit();
	private Reader stringReader = null;
	private HTMLDocument page = null;
	
	private methods = [:];
	
	/**
	 * Constructor with html filepath setter
	 * @param filePath
	 */
	public RhythmUI(String filePath) {
		this.filePath = filePath;
	}
	
	public RhythmUI() {
	}
	
	/**
	 * Gets server instance and sets the html filepath
	 * @param filePath Directory to set html path
	 * @return The Rhythm vert.x server
	 */
	public HttpServer getServerInstance(String filePath){
		this.filePath = filePath;
		return getServerInstance();
	}
	
	/**
	 * Singleton for Rhythms vert.x server
	 * @return
	 */
	public HttpServer getServerInstance(){
		if(server == null){
			server = vertx.createHttpServer();
			
			server.requestHandler({ request ->
				def file = "";
				if(!request.path.contains("..")){
					file = request.path;
				}
				
				vertx.fileSystem.readFile(constructFilePath(file)) { ar ->
					if (ar.succeeded) {
						try {
							def root = new XmlParser(new org.cyberneko.html.parsers.SAXParser()).parseText(ar.result.toString());
							
							def body = root."**".findAll { 
								try{ 
									return it.name().equalsIgnoreCase("body");
								} catch(Exception e) { 
									return false;
								} 
							}[0];
						
							body.children().add(0, new Node(null, 'script', [type : "text/javascript"], new File("js/RhythmJS.js").text));
						
							def writer = new StringWriter();
							new XmlNodePrinter(new PrintWriter(writer)).print(root);
							request.response.end(writer.toString());

						} catch (Exception e) {
							//TODO: Fail
							e.printStackTrace();
							return;
						}
					} else {
						System.err.println("Failed to find specified file: " + constructFilePath(file));
						//TODO: Error message
					}
				};
			});
		
			server.websocketHandler({ ws ->
				if (ws.path == "/rhythm") {
					ws.dataHandler{ buffer ->
						 def methodJson = new JsonSlurper().parseText(buffer.toString());
						 def response = [:];
						 def method = methods[methodJson.method.toString()];
						 def methodId = methodJson.methodId.toString();
						 if(method){
							 response = ["results" : method(methodJson.parameters as String[])];
						 }
						 response["methodId"] = methodId; 
						 ws.writeTextFrame(new JsonBuilder(response).toString());
					}
				} else {
					ws.reject()
				}        
			});
		
			server.listen(SERVER_PORT, SERVER_ACCEPT_LISTEN);
		}
		return server;
	}

	/**
	 * Starts Rhythm's UI with Chrome
	 * @param file The initial page to navigate to within the browser
	 * @return The process started by executing Chrome
	 */
	public Process startUI(String file) {
		// start chrome with flag and page
		server = getServerInstance();
		Process process = null;
		
		try {
			process = new ProcessBuilder("cmd", "/C", CHROME_LOCATION, CHROME_FLAG + constructWebPath(file)).start();
		} catch (IOException e) {
			System.out.println("Process Exception");
			// TODO Tell user chrome isn't working
		}
		
		return process;
	}
	
	/**
	 * Maps a closure to be triggered by a specific name
	 * @param name Name to trigger closure
	 * @param function Closure to trigger
	 */
	public void on(String name, Closure function){
		methods.putAt(name, function);		
	}
	
	/**
	 * Maps a closure to be triggered on a specific object by name
	 * @param object The object of the method
	 * @param method The name of the method ont he object and name to trigger method
	 */
	public void on(Object object, String method){
		methods.putAt(method, object.&"$method");
	}
	
	/**
	 * Maps a named method to be triggered on a specific object by a reference name
	 * @param object The object of the method
	 * @param method The name of the method to trigger on the object
	 * @param name The name to trigger the method
	 */
	public void on(Object object, String method, String name){
		on(name, object.&"$method");
	}

	/**
	 * Changes the html filepath
 	 * @param filePath The filepath for the html files
	 */
	public void changeFilePath(String filePath) {
		this.filePath = filePath;
	}
	
	/**
	 * Creates the absolute file path based on the file for the OS
	 * @param file The file to get the absolute file path for
	 * @return The absolute file path string to the file
	 */
	public String constructFilePath(String file){
		return getLocalPath() + file.replace("/",  "\\") ;
	}
	
	/**
	 * Creates the URL path to navigate to a specific file
	 * @param file The file to get the URL path to
	 * @return The URL string to the file
	 */
	public String constructWebPath(String file){
		return "http://localhost:" + SERVER_PORT + "/" + filePath + "/" + file + "\"";
	}
	
	/**
	 * Gets the local path to the current directory
	 * @return The user's current directory
	 */
	public static String getLocalPath(){
		return System.getProperty("user.dir");
	}
	
	public static void main(String[] args) throws InterruptedException{
		def ui = new RhythmUI("ui");
		ui.on(ui, "javaTest");
		Process p = ui.startUI("test.html");
		Thread.sleep(100000);
		//p.destroy();
	}
	
	public Map javaTest(String test, String test2){
		println("Java Parameter 1: " + test + ", Java Parameter 2: " + test2);
		return ["results" : "finished java"];
	}
	
}
