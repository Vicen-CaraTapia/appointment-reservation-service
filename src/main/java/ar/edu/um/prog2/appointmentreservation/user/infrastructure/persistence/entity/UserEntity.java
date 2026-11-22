package ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.entity;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "app_user")
public class UserEntity {

	private static final Pattern ARGON2ID_HASH = Pattern.compile(
			"\\$argon2id\\$v=\\d+\\$m=\\d+,t=\\d+,p=\\d+\\$[A-Za-z0-9+/]+\\$[A-Za-z0-9+/]+");

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "login", length = 50, nullable = false)
	private String login;

	@Column(name = "password_hash", length = 255, nullable = false)
	private String passwordHash;

	@Column(name = "first_name", length = 50)
	private String firstName;

	@Column(name = "last_name", length = 50)
	private String lastName;

	@Column(name = "email", length = 254, nullable = false)
	private String email;

	@Column(name = "image_url", length = 256)
	private String imageUrl;

	@Column(name = "lang_key", length = 10, nullable = false)
	private String langKey;

	@Column(name = "activated", nullable = false)
	private boolean activated;

	@Column(name = "created_by", length = 50, nullable = false, updatable = false)
	private String createdBy;

	@Column(name = "created_date", nullable = false, updatable = false)
	private Instant createdDate;

	@Column(name = "last_modified_by", length = 50)
	private String lastModifiedBy;

	@Column(name = "last_modified_date")
	private Instant lastModifiedDate;

	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(name = "app_user_authority",
			joinColumns = @JoinColumn(name = "user_id"),
			inverseJoinColumns = @JoinColumn(name = "authority_name"))
	private Set<AuthorityEntity> authorities = new HashSet<>();

	protected UserEntity() {
	}

	public UserEntity(String login, String encodedPassword, String firstName, String lastName,
			String email, String imageUrl, String langKey, String createdBy) {
		this.login = requiredText(login, "Login", 1, 50).toLowerCase(Locale.ROOT);
		if (encodedPassword == null || encodedPassword.length() > 255
				|| !ARGON2ID_HASH.matcher(encodedPassword).matches()) {
			throw new IllegalArgumentException("An encoded Argon2id hash is required");
		}
		this.passwordHash = encodedPassword;
		this.firstName = optionalText(firstName, "First name", 50);
		this.lastName = optionalText(lastName, "Last name", 50);
		this.email = requiredText(email, "Email", 5, 254).toLowerCase(Locale.ROOT);
		this.imageUrl = optionalText(imageUrl, "Image URL", 256);
		this.langKey = requiredText(langKey, "Language key", 2, 10);
		this.createdBy = requiredText(createdBy, "Audit actor", 1, 50);
	}

	public void updateProfile(String firstName, String lastName, String email, String imageUrl,
			String langKey, String modifiedBy) {
		String checkedFirstName = optionalText(firstName, "First name", 50);
		String checkedLastName = optionalText(lastName, "Last name", 50);
		String checkedEmail = requiredText(email, "Email", 5, 254).toLowerCase(Locale.ROOT);
		String checkedImageUrl = optionalText(imageUrl, "Image URL", 256);
		String checkedLangKey = requiredText(langKey, "Language key", 2, 10);
		recordModification(modifiedBy);
		this.firstName = checkedFirstName;
		this.lastName = checkedLastName;
		this.email = checkedEmail;
		this.imageUrl = checkedImageUrl;
		this.langKey = checkedLangKey;
	}

	public void activate(String modifiedBy) {
		recordModification(modifiedBy);
		this.activated = true;
	}

	public void grantAuthority(AuthorityEntity authority, String modifiedBy) {
		Objects.requireNonNull(authority, "Authority is required");
		recordModification(modifiedBy);
		this.authorities.add(authority);
	}

	private void recordModification(String actor) {
		String checkedActor = requiredText(actor, "Audit actor", 1, 50);
		if (id != null) {
			lastModifiedBy = checkedActor;
			// Also dirties the owner when only its authority collection changes.
			lastModifiedDate = Instant.now();
		}
	}

	@PrePersist
	private void recordCreationDate() {
		createdDate = Instant.now();
	}

	@PreUpdate
	private void recordModificationDate() {
		lastModifiedDate = Instant.now();
	}

	private static String requiredText(String value, String field, int min, int max) {
		if (value == null || value.isBlank() || value.length() < min || value.length() > max) {
			throw new IllegalArgumentException(field + " has an invalid length or is blank");
		}
		return value;
	}

	private static String optionalText(String value, String field, int max) {
		if (value != null && value.length() > max) {
			throw new IllegalArgumentException(field + " is too long");
		}
		return value;
	}

	public Long getId() { return id; }
	public String getLogin() { return login; }
	public String getPasswordHash() { return passwordHash; }
	public String getFirstName() { return firstName; }
	public String getLastName() { return lastName; }
	public String getEmail() { return email; }
	public String getImageUrl() { return imageUrl; }
	public String getLangKey() { return langKey; }
	public boolean isActivated() { return activated; }
	public String getCreatedBy() { return createdBy; }
	public Instant getCreatedDate() { return createdDate; }
	public String getLastModifiedBy() { return lastModifiedBy; }
	public Instant getLastModifiedDate() { return lastModifiedDate; }
	public Set<AuthorityEntity> getAuthorities() { return Collections.unmodifiableSet(authorities); }
}
