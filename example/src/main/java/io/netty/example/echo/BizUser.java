package io.netty.example.echo;

public class BizUser {

	private Integer age;

	private String name;

	public BizUser() {
	}

	public BizUser(Integer age, String name) {
		this.age = age;
		this.name = name;
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
