package fr.hoyatla.pauc.platform.forge.scheduler;

public enum PauCTaskLane {
	SERVER_PREPARE("server_prepare", "PauC-Server-Prepare"),
	CLIENT_PREPARE("client_prepare", "PauC-Client-Prepare"),
	IO("io", "PauC-IO");

	private final String id;
	private final String threadPrefix;

	PauCTaskLane(String id, String threadPrefix) {
		this.id = id;
		this.threadPrefix = threadPrefix;
	}

	public String id() {
		return id;
	}

	public String threadPrefix() {
		return threadPrefix;
	}
}
