package brotherdetjr.utils;

import com.google.common.base.Preconditions;
import lombok.experimental.UtilityClass;

@UtilityClass
public class Utils {
	@SuppressWarnings("ResultOfMethodCallIgnored")
	public static void checkNotNull(Object ... objects) {
		for (Object object : objects) {
			Preconditions.checkNotNull(object);
		}
	}

	public static void propagateIfError(Throwable e) {
		if (e instanceof Error) {
			throw (Error) e;
		}
	}
}
