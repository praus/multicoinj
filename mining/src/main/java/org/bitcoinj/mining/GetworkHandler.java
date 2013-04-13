package org.bitcoinj.mining;

import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONNECTION;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.util.List;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.bitcoin.core.Block;
import com.google.bitcoin.core.Utils;
import com.google.common.io.BaseEncoding;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class GetworkHandler extends SimpleChannelUpstreamHandler {

	final static Logger log = LoggerFactory.getLogger(GetworkHandler.class);
	
	private HttpRequest request;
	/** Buffer that stores the response content */
	private final StringBuilder buf = new StringBuilder();
	
	private final Miner miner;
	
	public GetworkHandler(Miner miner) {
		this.miner = miner;
	}

	class GetworkRequest {
		String method;
		List<String> params;
		long id;
	}
	
	static class GetworkResponse {
		static class Result {
			//String midstate;
			String data;
			//String hash1;
			String target;
			
			Result() {}
			
			Result(String data, String target) {
				this.data = data;
				this.target = target;
				//midstate = "1112b04e0fd4737a5ddec52ce2675d97c3c104592ddeaa90cfbd0c8ceab9a24f";
				//hash1 = "00000000000000000000000000000000000000000000000000000000000000000000008000000000000000000000000000000000000000000000000000010000";
			}
		}
		
		Result result;
		String error = null;
		long id;
		
		public GetworkResponse() {}
		
		public GetworkResponse(String data, String target, long id) {
			result = new Result(data, target);
			this.id = id;
		}
	}
	
	/**
	 * {"result": false, "id": "1", "error": null}
	 */
	class SubmitWorkResponse {
		boolean result;
		long id;
		String error = null;
		
		public SubmitWorkResponse() {}
		
		public SubmitWorkResponse(boolean result, long id) {
			this.result = result;
			this.id = id;
		}
	}
	
	@Override
	public void messageReceived(ChannelHandlerContext ctx, MessageEvent e)
			throws Exception {
		if (e.getMessage() instanceof HttpRequest) {
			request = (HttpRequest) e.getMessage();

			buf.setLength(0);

//			for (Map.Entry<String, String> h : request.getHeaders()) {
//				log.info("{}: {}", h.getKey(), h.getValue());
//			}
			
			if (request.isChunked()) {
				log.warn("Request is chunked");
			} else {
				ChannelBuffer content = request.getContent();
				if (content.readable()) {
					Gson gson = new GsonBuilder().serializeNulls().create();
					String body = content.toString(CharsetUtil.UTF_8);
					if (body.contains("getblocktemplate")) {
						buf.append(gson.toJson(new SubmitWorkResponse(false, 0)));
					} else {
						GetworkRequest getwork = gson.fromJson(body, GetworkRequest.class);
						
						if (getwork.params.size() >= 1) {
							// miner is submitting work
							byte [] payload = BaseEncoding.base16().lowerCase().decode(getwork.params.get(0));
							Block solvedBlock = new Block(miner.getParams(), Utils.reverseDwordBytes(payload, payload.length));
							boolean success = miner.submitBlock(solvedBlock);
							
							String ack = gson.toJson(new SubmitWorkResponse(success, getwork.id));
							buf.append(ack);
							
						} else {
							// miner wants new work
							miner.reloadBlock();
							
							String data = BaseEncoding.base16().lowerCase().encode(miner.getWork());
							String target = "0000000000000000000000000000000000000000000000000000ffff00000000";
							GetworkResponse gwRes = new GetworkResponse(data, target, getwork.id);
							String json = gson.toJson(gwRes);
							buf.append(json);
						}
					}
				}
				writeResponse(e);
			}
		} else {
			log.warn("Unknown message type");
		}
	}

	private void writeResponse(MessageEvent e) {
		// Decide whether to close the connection or not.
		boolean keepAlive = true;//isKeepAlive(request);

		// Build the response object.
		HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
		response.setContent(ChannelBuffers.copiedBuffer(buf.toString(), CharsetUtil.UTF_8));
		response.setHeader(CONTENT_TYPE, "text/application-json");
		response.setHeader("Server", "bitcoinj-miner");

		if (keepAlive) {
			// Add 'Content-Length' header only for a keep-alive connection.
			response.setHeader(CONTENT_LENGTH, response.getContent()
					.readableBytes());
			// Add keep alive header as per:
			// http://www.w3.org/Protocols/HTTP/1.1/draft-ietf-http-v11-spec-01.html#Connection
			response.setHeader(CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
		}

		// Write the response.
		ChannelFuture future = e.getChannel().write(response);

		// Close the non-keep-alive connection after the write operation is
		// done.
		if (!keepAlive) {
			future.addListener(ChannelFutureListener.CLOSE);
		}
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e)
			throws Exception {
		e.getCause().printStackTrace();
		e.getChannel().close();
	}

}
