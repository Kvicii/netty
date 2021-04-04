package io.netty.example.echo;

import io.netty.util.concurrent.FastThreadLocal;

import java.util.concurrent.TimeUnit;

public class FastThreadLocalTest {

	private static FastThreadLocal<Object> threadLocal = new FastThreadLocal<Object>() {
		/**
		 * Returns the initial value for this thread-local variable.
		 */
		@Override
		protected Object initialValue() throws Exception {
			return new Object();
		}
	};

	public static void main(String[] args) {
		new Thread(() -> {
			Object obj = threadLocal.get();
			System.out.println(obj);
			while (true) {
				threadLocal.set(new Object());
				try {
					TimeUnit.SECONDS.sleep(1);
				} catch (InterruptedException e) {
					//
				}
			}
		}).start();

		new Thread(() -> {
			Object obj = threadLocal.get();
			System.out.println(obj);
			while (true) {
				System.out.println(threadLocal.get().equals(obj));
				try {
					TimeUnit.SECONDS.sleep(1);
				} catch (InterruptedException e) {
					//
				}
			}
		}).start();

	}
}
