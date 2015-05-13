package org.jivesoftware.openfire.sso;

import java.util.Date;

import org.jivesoftware.openfire.user.User;

class VenusUser {
	static public final int SEX_UNKNOWN = 0;
	static public final int SEX_MALE = 1;
	static public final int SEX_WOMAN = 2;

	long uin;
	String name;
	String headerImgUrl;
	String phoneNO;
	int sexType; 

	public String getPhoneNO() {
		return phoneNO;
	}


	public void setPhoneNO(String phoneNO) {
		this.phoneNO = phoneNO;
	}


	public void setUin(long uin) {
		this.uin = uin;
	}


	public void setName(String name) {
		this.name = name;
	}


	public long getUin() {
		return uin;
	}

	public String getName() {
		return name;
	}

	public String getHeaderImgUrl() {
		return headerImgUrl;
	}

	public void setHeaderImgUrl(String headerImgUrl) {
		this.headerImgUrl = headerImgUrl;
	}
	
	
	
	public int getSexType() {
		return sexType;
	}


	public void setSexType(int sexType) {
		this.sexType = sexType;
	}


	public User getOpenfireUser(){
		Date curDate = new Date();
		User user = new User(Long.toString(uin), name, null, curDate, curDate);
		return user;
	}

}
