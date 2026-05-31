package fr.hoyatla.pauc.platform.forge.scheduler;

public enum PauCTaskPriority {
	CRITICAL(0),
	FOV(1),
	ACTIVE(2),
	BACKGROUND(3);

	private final int order;

	PauCTaskPriority(int order) {
		this.order = order;
	}

	public int order() {
		return order;
	}
}
