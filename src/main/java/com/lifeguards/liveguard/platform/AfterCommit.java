package com.lifeguards.liveguard.platform;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 트랜잭션 안이면 커밋 후에, 밖이면 바로 실행한다. 롤백되면 실행하지 않는다. */
final class AfterCommit {

	private AfterCommit() {
	}

	static void run(Runnable action) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					action.run();
				}
			});
		} else {
			action.run();
		}
	}

}
