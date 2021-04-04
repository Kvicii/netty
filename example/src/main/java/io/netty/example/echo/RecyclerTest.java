package io.netty.example.echo;

import io.netty.util.Recycler;

public class RecyclerTest {

	private static final Recycler<BizUser> RECYCLER = new Recycler<BizUser>() {
		@Override
		protected BizUser newObject(Handle<BizUser> handle) {
			return new BizUser(handle);
		}
	};

	public static void main(String[] args) {
		BizUser user1 = RECYCLER.get();
		user1.recycle();
		BizUser user2 = RECYCLER.get();
		System.out.println(user1 == user2);
	}
}
