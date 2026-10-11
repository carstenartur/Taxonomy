package testfixtures.restore;

/** Explicitly registered only by the restore model-drift test, outside the application's entity scan. */
@jakarta.persistence.Entity
@jakarta.persistence.Table(name = "unknown_future_owner")
public class UnknownFutureOwner {
    @jakarta.persistence.Id
    private long id;
}
