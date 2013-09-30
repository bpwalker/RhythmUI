package org.rhythm

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper

import javax.swing.text.html.HTMLDocument
import javax.swing.text.html.HTMLEditorKit

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
	
	
	public RhythmUI(String filePath) {
		super();
		this.filePath = filePath;
	}
	
	public RhythmUI() {
		super();
	}
	
	public HttpServer getServerInstance(String filePath){
		this.filePath = filePath;
		return getServerInstance();
	}
	
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
							//stringReader = new StringReader(ar.result.toString());
				        	//page = (HTMLDocument) htmlKit.createDefaultDocument();
							//htmlKit.read(stringReader, page, 0);
							//Element endOfPage = page.getElement(page.getDefaultRootElement(), StyleConstants.NameAttribute, HTML.Tag.HTML);
					        //page.insertBeforeEnd(endOfPage, "<div>inject</div>");
					        //StringWriter stringWriter = new StringWriter();
					        //htmlKit.write(stringWriter, page, 0, page.getLength());

							//request.response.end(stringWriter.toString());
							
							request.response.end(ar.result.toString());
							
							
							//TODO: Inject javascript library
						} catch (Exception e) {
							//TODO: Fail
							e.printStackTrace();
							return;
						}
					} else {
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
	public void on(String name, Closure function){
		methods.put(name, function);		
	}
	
	public def on(Object object, String method){
		methods.putAt(method, object.&"$method");
	}

	public void changeFilePath(String filePath) {
		this.filePath = filePath;
	}
	
	public String constructFilePath(String file){
		return getLocalPath() + file.replace("/",  "\\") ;
	}
	
	public String constructWebPath(String file){
		return "http://localhost:" + SERVER_PORT + "/" + filePath + "/" + file + "\"";
	}
	
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
