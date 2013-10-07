var RhythmUI = (function RhythmUI(){ 
	
	var instance;
	
	function init(){
	
		var callBacks = {}; 
		var socket = new WebSocket("ws://localhost:8686/rhythm");
		var socketInitialized = false;
		var preSockInitCalls = [];
		
		RhythmUI = function(){
			return instance();
		}
		
		RhythmUI.prototype = this;
		
		instance = new RhythmUI();
		
		if(!socket){
			window.close();
		}
		
		socket.onmessage = function(event) {
			var data = JSON.parse(event.data);
		   	var cb = callBacks[data.methodId];
		   	if(cb){
		   		delete callBacks[data.methodId];
		   		cb(data.results);
		   	}
		}
		
		socket.onopen = function(event) {
			//Send stored calls
			preSockInitCalls.forEach(function(e){
				socket.send(e);
			});
			socketInitialized = true;
		};
			
		socket.onclose = function(event) {
		   window.close();
		};
		
		return{
			call : function(method, parameters, cb){
				var methodId =  method + "_" + (new Date()).getTime();
				callBacks[methodId] = cb;
				var params = JSON.stringify({"method" : method, "parameters" : parameters, "methodId" : methodId });
				
				//Check if socket has been initialized. If not, save it to array to be sent after initialization.
				if(socketInitialized){
					socket.send(params);
				} else{
					preSockInitCalls.push(params);
				}
			},
			
			navigate : function(page){
				window.location = window.location.origin + "/" + page;
			}
		}
	};
	
	return{
		getInstance: function () {
			if(!instance){
        		instance = init();
      		}

     		return instance;
    	}
  	};	
})();

var rhythm = RhythmUI.getInstance();
window.rhythm = rhythm;