/**
 *Copyright [2009-2010] [dennis zhuang(killme2008@gmail.com)]
 *Licensed under the Apache License, Version 2.0 (the "License");
 *you may not use this file except in compliance with the License. 
 *You may obtain a copy of the License at 
 *             http://www.apache.org/licenses/LICENSE-2.0 
 *Unless required by applicable law or agreed to in writing, 
 *software distributed under the License is distributed on an "AS IS" BASIS, 
 *WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, 
 *either express or implied. See the License for the specific language governing permissions and limitations under the License
 */
package net.rubyeye.xmemcached;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import net.rubyeye.xmemcached.command.Command;
import net.spy.memcached.transcoders.CachedData;
import net.spy.memcached.transcoders.Transcoder;

import com.google.code.yanf4j.nio.Session;
import com.google.code.yanf4j.nio.impl.HandlerAdapter;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import net.rubyeye.xmemcached.exception.MemcachedClientException;
import net.rubyeye.xmemcached.exception.MemcachedException;
import net.rubyeye.xmemcached.exception.MemcachedServerException;
import net.rubyeye.xmemcached.utils.ByteBufferMatcher;

/**
 * 核心类，负责协议解析和消息派发
 * 
 * @author dennis
 * 
 */
public class MemcachedHandler extends HandlerAdapter<Command> implements
		MemcachedProtocolHandler {

	private static final ByteBuffer SPLIT = ByteBuffer.wrap(Command.SPLIT
			.getBytes());

	/**
	 * BM算法匹配器，用于匹配行
	 */
	static ByteBufferMatcher SPLIT_MATCHER = new ByteBufferMatcher(SPLIT);

	/**
	 * 返回boolean值并唤醒
	 * 
	 * @param result
	 * @return
	 */
	private boolean notifyBoolean(MemcachedTCPSession session, Boolean result) {
		final Command executingCmd = session.getCurrentExecutingCommand();
		executingCmd.setResult(result);
		executingCmd.getLatch().countDown();
		session.resetStatus();
		return true;
	}

	/**
	 * 解析状态
	 * 
	 * @author dennis
	 * 
	 */
	enum ParseStatus {

		NULL, GET, END, STORED, NOT_STORED, ERROR, CLIENT_ERROR, SERVER_ERROR, DELETED, NOT_FOUND, VERSION, INCR, EXISTS;
	}

	int count = 0;

	public boolean onReceive(MemcachedTCPSession session, ByteBuffer buffer) {
		int origPos = buffer.position();
		int origLimit = buffer.limit();
		LABEL: while (true) {
			switch (session.status) {
			case NULL:
				nextLine(session, buffer);
				if (session.currentLine == null) {
					return false;
				}
				if (session.currentLine.startsWith("VALUE")) {
					session.currentValues = new HashMap<String, CachedData>();
					session.status = ParseStatus.GET;
				} else if (session.currentLine.equals("STORED")) {
					return notifyBoolean(session, Boolean.TRUE);
				} else if (session.currentLine.equals("DELETED")) {
					return notifyBoolean(session, Boolean.TRUE);
				} else if (session.currentLine.equals("END")) {
					return parseEndCommand(session);
				} else if (session.currentLine.equals("EXISTS")) {
					return notifyBoolean(session, Boolean.FALSE);
				} else if (session.currentLine.equals("NOT_STORED")) {
					return notifyBoolean(session, Boolean.FALSE);
				} else if (session.currentLine.equals("NOT_FOUND")) {
					return notifyBoolean(session, Boolean.FALSE);
				} else if (session.currentLine.equals("ERROR")) {
					return parseException(session);
				} else if (session.currentLine.startsWith("CLIENT_ERROR")) {
					return parseClientException(session);
				} else if (session.currentLine.startsWith("SERVER_ERROR")) {
					return parseServerException(session);
				} else if (session.currentLine.startsWith("VERSION ")) {
					return parseVersionCommand(session);
				} else {
					return parseIncrDecrCommand(session);
				}
				if (!session.status.equals(ParseStatus.NULL)) {
					continue LABEL;
				} else {
					log.error("unknow response:" + session.currentLine);
					throw new IllegalStateException("unknown response:"
							+ session.currentLine);
				}
			case GET:
				return parseGet(session, buffer, origPos, origLimit);
			default:
				return false;

			}
		}
	}

	/**
	 * 解析get协议response
	 * 
	 * @param buffer
	 * @param origPos
	 * @param origLimit
	 * @return
	 */
	private boolean parseGet(MemcachedTCPSession session, ByteBuffer buffer,
			int origPos, int origLimit) {
		while (true) {
			nextLine(session, buffer);
			if (session.currentLine == null) {
				return false;
			}
			if (session.currentLine.equals("END")) {
				Command executingCommand = session.getCurrentExecutingCommand();
				if (executingCommand == null) {
					return false;
				}
				if (executingCommand.getCommandType().equals(
						Command.CommandType.GET_MANY)
						|| executingCommand.getCommandType().equals(
								Command.CommandType.GETS_MANY)) {
					processGetManyCommand(session, session.currentValues,
							executingCommand);
				} else {
					processGetOneCommand(session, session.currentValues,
							executingCommand);
				}
				session.currentValues = null;
				session.resetStatus();
				return true;
			} else if (session.currentLine.startsWith("VALUE")) {
				String[] items = session.currentLine.split(" ");
				int flag = Integer.parseInt(items[2]);
				int dataLen = Integer.parseInt(items[3]);
				// 不够数据，返回
				if (buffer.remaining() < dataLen + 2) {
					buffer.position(origPos).limit(origLimit);
					session.currentLine = null;
					return false;
				}
				// 可能是gets操作
				long casId = -1;
				if (items.length >= 5)
					casId = Long.parseLong(items[4]);
				byte[] data = new byte[dataLen];
				buffer.get(data);
				session.currentValues.put(items[1], new CachedData(flag, data,
						dataLen, casId));
				buffer.position(buffer.position() + SPLIT.remaining());
				session.currentLine = null;
			} else {
				buffer.position(origPos).limit(origLimit);
				session.currentLine = null;
				return false;
			}

		}
	}

	/**
	 * 解析get协议返回空
	 * 
	 * @return
	 */
	private boolean parseEndCommand(MemcachedTCPSession session) {
		Command executingCmd = session.getCurrentExecutingCommand();
		int mergCount = executingCmd.getMergeCount();
		if (mergCount < 0) {
			// single
			executingCmd.setResult(null);
			executingCmd.getLatch().countDown();
		} else {
			// merge get
			List<Command> mergeCommands = executingCmd.getMergeCommands();
			for (Command nextCommand : mergeCommands) {
				nextCommand.setResult(null);
				nextCommand.getLatch().countDown(); // notify
			}

		}
		session.resetStatus();
		return true;

	}

	/**
	 * 解析错误response
	 * 
	 * @return
	 */
	private boolean parseException(MemcachedTCPSession session) {
		Command executingCmd = session.getCurrentExecutingCommand();
		final MemcachedException exception = new MemcachedException(
				"Unknown command,please check your memcached version");
		executingCmd.setException(exception);
		executingCmd.getLatch().countDown();
		session.resetStatus();
		return true;
	}

	/**
	 * 解析错误response
	 * 
	 * @return
	 */
	private boolean parseClientException(MemcachedTCPSession session) {
		String[] items = session.currentLine.split(" ");
		final String error = items.length > 1 ? items[1]
				: "unknown client error";
		Command executingCmd = session.getCurrentExecutingCommand();

		final MemcachedClientException exception = new MemcachedClientException(
				error);
		executingCmd.setException(exception);
		executingCmd.getLatch().countDown();
		session.resetStatus();

		return true;
	}

	/**
	 * 解析错误response
	 * 
	 * @return
	 */
	private boolean parseServerException(MemcachedTCPSession session) {
		String[] items = session.currentLine.split(" ");
		final String error = items.length > 1 ? items[1]
				: "unknown server error";
		Command executingCmd = session.getCurrentExecutingCommand();
		final MemcachedServerException exception = new MemcachedServerException(
				error);
		executingCmd.setException(exception);
		executingCmd.getLatch().countDown();
		session.resetStatus();
		return true;

	}

	/**
	 * 解析version协议response
	 * 
	 * @return
	 */
	private boolean parseVersionCommand(MemcachedTCPSession session) {
		String[] items = session.currentLine.split(" ");
		final String version = items.length > 1 ? items[1] : "unknown version";
		Command executingCmd = session.getCurrentExecutingCommand();
		executingCmd.setResult(version);
		executingCmd.getLatch().countDown();
		session.resetStatus();
		return true;

	}

	/**
	 * 解析incr,decr协议response
	 * 
	 * @return
	 */
	private boolean parseIncrDecrCommand(MemcachedTCPSession session) {
		final Integer result = Integer.parseInt(session.currentLine);
		Command executingCmd = session.getCurrentExecutingCommand();
		executingCmd.setResult(result);
		executingCmd.getLatch().countDown();
		session.resetStatus();

		return true;

	}

	/**
	 * 获取下一行
	 * 
	 * @param buffer
	 */
	protected void nextLine(MemcachedTCPSession session, ByteBuffer buffer) {
		if (session.currentLine != null) {
			return;
		}

		/**
		 * 测试表明采用BM算法匹配效率 > 朴素匹配 > KMP匹配，
		 * 如果你有更好的建议，请email给我
		 */
		int index = SPLIT_MATCHER.matchFirst(buffer);
		// int index = ByteBufferUtils.indexOf(buffer, SPLIT);
		if (index >= 0) {
			int limit = buffer.limit();
			buffer.limit(index);
			byte[] bytes = new byte[buffer.remaining()];
			buffer.get(bytes);
			buffer.limit(limit);
			buffer.position(index + SPLIT.remaining());
			try {
				session.currentLine = new String(bytes, "utf-8");
			} catch (UnsupportedEncodingException e) {
			}

		} else {
			session.currentLine = null;
		}

	}

	/**
	 * HandlerAdapter实现，负责命令管理和派发
	 */
	@SuppressWarnings("unchecked")
	private Transcoder transcoder;
	protected XMemcachedClient client;
	protected static final Log log = LogFactory.getLog(MemcachedHandler.class);

	@Override
	public void onMessageSent(Session session, Command t) {
		((MemcachedTCPSession) session).executingCmds.add(t);
	}

	@Override
	public void onSessionStarted(Session session) {
		// 启用阻塞读写，因为memcached通常跑在局域网内，网络状况良好，采用阻塞读写效率更好
		session.setUseBlockingWrite(true);
		session.setUseBlockingRead(true);
	}

	@Override
	public void onSessionClosed(Session session) {
		this.client.getConnector().removeSession((MemcachedTCPSession) session);
		reconnect(session);
	}

	protected void reconnect(Session session) {
		if (!this.client.isShutdown()) {
			this.client.getConnector().addToWatingQueue(
					session.getRemoteSocketAddress());
		}
	}

	@SuppressWarnings("unchecked")
	private void processGetOneCommand(Session session,
			Map<String, CachedData> values, Command executingCmd) {
		int mergeCount = executingCmd.getMergeCount();
		if (mergeCount < 0) {
			// single get
			if (values.get(executingCmd.getKey()) == null)
				reconnect(session);
			else {
				CachedData data = values.get(executingCmd.getKey());
				executingCmd.setResult(data); // 设置CachedData返回，transcoder.decode()放到用户线程
				executingCmd.getLatch().countDown();
			}
		} else {
			// merge get
			List<Command> mergeCommands = executingCmd.getMergeCommands();
			executingCmd.getByteBufferWrapper().free();
			for (Command nextCommand : mergeCommands) {
				nextCommand.setResult(values.get(nextCommand.getKey()));
				nextCommand.getLatch().countDown();
			}
		}

	}

	@SuppressWarnings("unchecked")
	private void processGetManyCommand(Session session,
			Map<String, CachedData> values, Command executingCmd) {
		// 合并结果
		if (executingCmd.getCommandType().equals(Command.CommandType.GETS_MANY)) {
			Map result = (Map) executingCmd.getResult();
			Iterator<Map.Entry<String, CachedData>> it = values.entrySet()
					.iterator();
			while (it.hasNext()) {
				Map.Entry<String, CachedData> item = it.next();
				GetsResponse getsResult = new GetsResponse(item.getValue()
						.getCas(), transcoder.decode(item.getValue()));
				result.put(item.getKey(), getsResult);
			}
		} else {
			Map result = (Map) executingCmd.getResult();
			Iterator<Map.Entry<String, CachedData>> it = values.entrySet()
					.iterator();
			while (it.hasNext()) {
				Map.Entry<String, CachedData> item = it.next();
				result.put(item.getKey(), transcoder.decode(item.getValue()));
			}
		}
		executingCmd.getLatch().countDown();
	}

	@SuppressWarnings("unchecked")
	public MemcachedHandler(Transcoder transcoder, XMemcachedClient client) {
		super();
		this.transcoder = transcoder;
		this.client = client;
	}

	@SuppressWarnings("unchecked")
	public Transcoder getTranscoder() {
		return transcoder;
	}

	@SuppressWarnings("unchecked")
	public void setTranscoder(Transcoder transcoder) {
		this.transcoder = transcoder;
	}

	// public static void main(String[] args) throws Exception {
	// String line = "VALUE test 0 0 1 10\\r\\nVALUE test 0 0 1 10\\r\\nVERSION
	// 1.2.4\r\nSTORED\r\nDELETED\r\n";
	//
	// ByteBuffer buffer = ByteBuffer.wrap(line.getBytes());
	// int index = -1;
	// long start = System.currentTimeMillis();
	// for (int i = 0; i < 1000000; i++)
	// //index=SPLIT_MATCHER.matchFirst(buffer);
	// index=ByteBufferUtils.kmpIndexOf(buffer, SPLIT);
	// System.out.println(System.currentTimeMillis() - start);
	// System.out.println(index);
	//
	// }
}
