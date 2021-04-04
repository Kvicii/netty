package io.netty.example.echo;

import io.netty.util.Recycler;

public class BizUser {

	private Integer age;

	private String name;

	public BizUser() {
	}

	public BizUser(Recycler.Handle<BizUser> handle) {
		this.handle = handle;
	}

	public BizUser(Integer age, String name) {
		this.age = age;
		this.name = name;
	}

	private Recycler.Handle<BizUser> handle;

	public void recycle() {
		handle.recycle(this);
	}

	public Integer getAge() {
		return age;
	}

	public void setAge(Integer age) {
		this.age = age;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}
}
