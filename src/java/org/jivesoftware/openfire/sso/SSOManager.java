/*
 * Copyright (C) 2012 Issa Gorissen <issa-gorissen@usa.net>. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jivesoftware.openfire.sso;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.bind.JAXB;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;
import org.apache.commons.httpclient.Credentials;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.HttpState;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.httpclient.NameValuePair;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.params.HttpClientParams;
import org.apache.commons.httpclient.params.HttpConnectionManagerParams;
import org.apache.commons.lang.StringUtils;
import org.jivesoftware.openfire.crowd.jaxb.Group;
import org.jivesoftware.openfire.crowd.jaxb.Groups;
import org.jivesoftware.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;


public class SSOManager {
	private static final Logger LOG = LoggerFactory.getLogger(SSOManager.class);
	private static final Object O = new Object();
	private static final String APPLICATION_JSON = "application/json";
	private static final Header HEADER_ACCEPT_APPLICATION_JSON = new Header("Accept", APPLICATION_JSON);
	private static final Header HEADER_ACCEPT_CHARSET_UTF8 = new Header("Accept-Charset", "UTF-8");

	private static SSOManager INSTANCE;
	
	private HttpClient client;
	private URI ssoServer;

	public static SSOManager getInstance() {
		if (INSTANCE == null) {
			synchronized (O) {
				if (INSTANCE == null) {
					SSOManager manager = new SSOManager();
					if (manager != null)
						INSTANCE = manager;
				}
			}
		}
		return INSTANCE;
	}
	
	private SSOManager() {
		try {
			// loading crowd.properties file
			SSOProperties ssoProps = new SSOProperties();
			
			MultiThreadedHttpConnectionManager threadedConnectionManager = new MultiThreadedHttpConnectionManager();
			HttpClient hc = new HttpClient(threadedConnectionManager);
	
			HttpClientParams hcParams = hc.getParams();
			hcParams.setAuthenticationPreemptive(true);
			
			HttpConnectionManagerParams hcConnectionParams = hc.getHttpConnectionManager().getParams();
			hcConnectionParams.setDefaultMaxConnectionsPerHost(ssoProps.getHttpMaxConnections());
			hcConnectionParams.setMaxTotalConnections(ssoProps.getHttpMaxConnections());
			hcConnectionParams.setConnectionTimeout(ssoProps.getHttpConnectionTimeout());
			hcConnectionParams.setSoTimeout(ssoProps.getHttpSocketTimeout());
			
			ssoServer = new URI(ssoProps.getSSOServerUrl());
			
			// setting BASIC authentication in place for connection with Crowd
			HttpState httpState = hc.getState();
			Credentials crowdCreds = new UsernamePasswordCredentials(ssoProps.getApplicationName(), ssoProps.getApplicationPassword());
			httpState.setCredentials(new AuthScope(ssoServer.getHost(), ssoServer.getPort()), crowdCreds);
			
			// setting Proxy config in place if needed
			if (StringUtils.isNotBlank(ssoProps.getHttpProxyHost()) && ssoProps.getHttpProxyPort() > 0) {
				hc.getHostConfiguration().setProxy(ssoProps.getHttpProxyHost(), ssoProps.getHttpProxyPort());
				
				if (StringUtils.isNotBlank(ssoProps.getHttpProxyUsername()) || StringUtils.isNotBlank(ssoProps.getHttpProxyPassword())) {
					Credentials proxyCreds = new UsernamePasswordCredentials(ssoProps.getHttpProxyUsername(), ssoProps.getHttpProxyPassword());
					httpState.setProxyCredentials(new AuthScope(ssoProps.getHttpProxyHost(), ssoProps.getHttpProxyPort()), proxyCreds);
				}
			}
			
			if (LOG.isDebugEnabled()) {
				LOG.debug("HTTP Client config");
				LOG.debug(ssoServer.toString());
				LOG.debug("Max connections:" + hcConnectionParams.getMaxTotalConnections());
				LOG.debug("Socket timeout:" + hcConnectionParams.getSoTimeout());
				LOG.debug("Connect timeout:" + hcConnectionParams.getConnectionTimeout());
				LOG.debug("Proxy host:" + ssoProps.getHttpProxyHost() + ":" + ssoProps.getHttpProxyPort());
				LOG.debug("sso application name:" + ssoProps.getApplicationName());
			}
			
			client = hc;
		} catch (Exception e) {
			LOG.error("Failure to load the sso manager", e);
		}
	}
	
	
	
	/**
	 * Authenticates a user with crowd. If authentication failed, raises a <code>RemoteException</code>
	 * @param username
	 * @param password
	 * @throws RemoteException
	 * @throws UnsupportedEncodingException 
	 */
	public void authenticate(String username, String password) throws RemoteException {
		username = JID.unescapeNode(username);
		if (LOG.isDebugEnabled()) LOG.debug("authenticate '" + String.valueOf(username) + "'");
		
		PostMethod post = new PostMethod(ssoServer.resolve("login").toString());

		NameValuePair[] values = new NameValuePair[2];
		values[0] = new NameValuePair("uid", username);
		values[1] = new NameValuePair("password", password);
		post.setRequestBody(values);
		
		try {
			int httpCode = client.executeMethod(post);
			if (httpCode != 200) {
				handleHTTPError(post);
			}
			
		} catch (IOException ioe) {
			handleError(ioe);
		} finally {
			post.releaseConnection();
		}
		
		LOG.info("authenticated user:" + username);
	}
	
	private List<VenusUser> parseVenusUserList(String response) {
		List<VenusUser> users = new ArrayList<VenusUser>();
		
		try {
			JSONObject respObj = new JSONObject(response);
			int statecode = respObj.optInt("statecode", -1);
			JSONObject body = respObj.getJSONObject("body");
			if( statecode != 0 || body == null){
				LOG.error("statecode =" + statecode );
				return null;
			}
			
			JSONArray userArray = body.getJSONArray("list");
			for(int i = 0; i < userArray.length(); i++){
				
				JSONObject userObj;
				try {
					userObj = userArray.getJSONObject(i);
					VenusUser user = new VenusUser();
					user.setUin(userObj.getInt("uin"));
					user.setName(userObj.optString("name"));
					user.setPhoneNO(userObj.optString("phone"));
					user.setHeaderImgUrl(userObj.optString("avatar_id"));
					user.setSexType(userObj.optInt("sex", VenusUser.SEX_UNKNOWN));
					users.add(user);
				} catch (JSONException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
	
			}
		} catch (JSONException e1) {
			e1.printStackTrace();
		}
		return users;
	}
	
	/**
	 * Get all the users from Crowd
	 * @return a List of User containing all the users stored in Crowd
	 * @throws RemoteException
	 */
	public List<VenusUser> getAllUsers() throws RemoteException {
		if (LOG.isDebugEnabled()) LOG.debug("fetching all crowd users");
		
		int maxResults = 100;
		int startIndex = 0;
		List<VenusUser> results = new ArrayList<VenusUser>();
		StringBuilder request = new StringBuilder("users?")
			.append("maxItemPerPage=").append(maxResults)
			.append("&fromIndex=0");
		
		try {
			while (true) {
				GetMethod get = createGetMethodJSONResponse(ssoServer.resolve(request.toString() + startIndex));
				List<VenusUser> users = null;
				try {
					int httpCode = client.executeMethod(get);
					if (httpCode != 200) {
						handleHTTPError(get);
					}
					String response = get.getResponseBodyAsString();
					users = parseVenusUserList(response);
				}finally {
					get.releaseConnection();
				}
				
				if(users == null){
					break;
				}
				results.addAll(users);
				
				if (users.size() != maxResults) {
					break;
				} else {
					startIndex += maxResults;
				}
			}
			
		} catch (IOException ioe) {
			handleError(ioe);
		}
		
		return results;
	}
	
	
	/**
	 * Get all the crowd groups
	 * @return a List of group names
	 * @throws RemoteException
	 */
	public List<String> getAllGroupNames() throws RemoteException {
		if (LOG.isDebugEnabled()) LOG.debug("fetch all crowd groups");

		int maxResults = 100;
		int startIndex = 0;
		List<String> results = new ArrayList<String>();
		StringBuilder request = new StringBuilder("groups")
			.append("?maxItemPerPage=").append(maxResults)
			.append("&fromIndex=0");
		
		try {
			while (true) {
				GetMethod get = createGetMethodJSONResponse(ssoServer.resolve(request.toString() + startIndex));
				Groups groups = null;
				
				try {
					int httpCode = client.executeMethod(get);
					if (httpCode != 200) {
						handleHTTPError(get);
					}
					String response = get.getResponseBodyAsString();
					//TODO: parse response
				} finally {
					get.releaseConnection();
				}
				
				if (groups != null && groups.group != null) {
					for (Group group : groups.group) {
						results.add(group.name);
					}
					
					if (groups.group.size() != maxResults) {
						break;
					} else {
						startIndex += maxResults;
					}
				} else {
					break;
				}
			}
			
		} catch (IOException ioe) {
			handleError(ioe);
		}
		
		return results;
	}
	
	
	/**
	 * Get all the groups of a given username
	 * @param username
	 * @return a List of groups name
	 * @throws RemoteException
	 */
	public List<String> getUserGroups(String username) throws RemoteException {
		username = JID.unescapeNode(username);
		if (LOG.isDebugEnabled()) LOG.debug("fetch all venus groups for user:" + username);
		
		int maxResults = 100;
		int startIndex = 0;
		List<String> results = new ArrayList<String>();
		StringBuilder request = new StringBuilder("groups/nested?username=").append(urlEncode(username))
			.append("&maxItemPerPage=").append(maxResults)
			.append("&fromIndex=0");
		
		try {
			while (true) {
				GetMethod get = createGetMethodJSONResponse(ssoServer.resolve(request.toString() + startIndex));
				Groups groups = null;
				
				try {
					int httpCode = client.executeMethod(get);
					if (httpCode != 200) {
						handleHTTPError(get);
					}
					String response = get.getResponseBodyAsString();
					//TODO: parse response
				} finally {
					get.releaseConnection();
				}
				
				if (groups != null && groups.group != null) {
					for (Group group : groups.group) {
						results.add(group.name);
					}
					
					if (groups.group.size() != maxResults) {
						break;
					} else {
						startIndex += maxResults;
					}
				} else {
					break;
				}
			}
			
		} catch (IOException ioe) {
			handleError(ioe);
		}
		
		return results;
	}
	
	
	/**
	 * Get the description of a group from crowd
	 * @param groupName
	 * @return a Group object
	 * @throws RemoteException
	 */
	public Group getGroup(String groupName) throws RemoteException {
		if (LOG.isDebugEnabled()) LOG.debug("Get group:" + groupName + " from crowd");
		
		GetMethod get = createGetMethodJSONResponse(ssoServer.resolve("groups/" + urlEncode(groupName)));
		Group group = null;
		
		try {
			int httpCode = client.executeMethod(get);
			if (httpCode != 200) {
				handleHTTPError(get);
			}
			
			String response = get.getResponseBodyAsString();
			//TODO: parse response
			
		} catch (IOException ioe) {
			handleError(ioe);
		} finally {
			get.releaseConnection();
		}
		
		return group;
	}
	
	
	/**
	 * Get the members of the given group
	 * @param groupName
	 * @return a List of String with the usernames members of the given group
	 * @throws RemoteException
	 */
	public List<String> getGroupMembers(String groupName) throws RemoteException {
		if (LOG.isDebugEnabled()) LOG.debug("Get all members for group:" + groupName);
		
		int maxResults = 100;
		int startIndex = 0;
		List<String> results = new ArrayList<String>();
		StringBuilder request = new StringBuilder("group/").append(urlEncode(groupName)).append("/users?")
			.append("&maxItemPerPage=").append(maxResults)
			.append("&fromIndex=0");
		
		try {
			while (true) {
				GetMethod get = createGetMethodJSONResponse(ssoServer.resolve(request.toString() + startIndex));
				List<VenusUser> users = null;
				
				try {
					int httpCode = client.executeMethod(get);
					if (httpCode != 200) {
						handleHTTPError(get);
					}
					String response = get.getResponseBodyAsString();
					users = parseVenusUserList(response);
				} finally {
					get.releaseConnection();
				}
				
				if (users != null) {
					for (VenusUser user : users) {
						results.add(JID.escapeNode(user.getName()));
					}
					
					if (users.size() != maxResults) {
						break;
					} else {
						startIndex += maxResults;
					}
				} else {
					break;
				}
			}
			
		} catch (IOException ioe) {
			handleError(ioe);
		}
		
		return results;
	}
	
	private String urlEncode(String str) {
		try {
			return URLEncoder.encode(str, "UTF-8");
		} catch (UnsupportedEncodingException uee) {
			LOG.error("UTF-8 not supported ?", uee);
			return str;
		}
	}
	
	
	private void handleHTTPError(HttpMethod method) throws RemoteException {
		int status = method.getStatusCode();
		String statusText = method.getStatusText();
		String body = null;
		try {
			body = method.getResponseBodyAsString();
		} catch (IOException ioe) {
			LOG.warn("Unable to retreive Crowd http response body", ioe);
		}
		
		StringBuilder strBuf = new StringBuilder();
		strBuf.append("Crowd returned HTTP error code:").append(status);
		strBuf.append(" - ").append(statusText);
		if (StringUtils.isNotBlank(body)) {
			strBuf.append("\n").append(body);
		}
		
		throw new RemoteException(strBuf.toString());
	}
	
	private void handleError(Exception e) throws RemoteException {
		LOG.error("Error occured while consuming Crowd REST service", e);
		throw new RemoteException(e.getMessage());
	}
	
	private GetMethod createGetMethodJSONResponse(URI uri) {
		GetMethod get = new GetMethod(uri.toString());
		get.addRequestHeader(HEADER_ACCEPT_APPLICATION_JSON);
		get.addRequestHeader(HEADER_ACCEPT_CHARSET_UTF8);
		return get;
	}

}
