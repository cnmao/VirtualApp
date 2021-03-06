package com.lody.virtual.client;

import android.annotation.SuppressLint;
import android.app.Application;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;

import com.lody.virtual.IOHook;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.env.VirtualRuntime;
import com.lody.virtual.client.fixer.ContextFixer;
import com.lody.virtual.client.hook.delegate.AppInstrumentation;
import com.lody.virtual.client.hook.providers.ProviderHook;
import com.lody.virtual.client.hook.secondary.ProxyServiceFactory;
import com.lody.virtual.client.local.VActivityManager;
import com.lody.virtual.client.local.VPackageManager;
import com.lody.virtual.helper.utils.Reflect;
import com.lody.virtual.helper.utils.VLog;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import dalvik.system.PathClassLoader;
import mirror.android.app.ActivityThread;
import mirror.android.app.ContextImpl;
import mirror.android.app.LoadedApk;
import mirror.com.android.internal.content.ReferrerIntent;
import mirror.dalvik.system.VMRuntime;

import static com.lody.virtual.os.VUserHandle.getUserId;

/**
 * @author Lody
 */

public final class VClientImpl extends IVClient.Stub {

	private static final int NEW_INTENT = 11;

	private static final String TAG = VClientImpl.class.getSimpleName();

	@SuppressLint("StaticFieldLeak")
	private static final VClientImpl gClient = new VClientImpl();
	private Instrumentation mInstrumentation = AppInstrumentation.getDefault();
	private static final Pattern sSplitAuthorityPattern = Pattern.compile(";");

	private IBinder token;
	private int vuid;
	private final H mH = new H();
	private AppBindData mBoundApplication;
	private Application mInitialApplication;

	public boolean isBound() {
		return mBoundApplication != null;
	}

	public Application getCurrentApplication() {
		return mInitialApplication;
	}

	public String getCurrentPackage() {
		return mBoundApplication != null ? mBoundApplication.appInfo.packageName : null;
	}

	public int getVUid() {
		return vuid;
	}

	public ClassLoader getClassLoader(ApplicationInfo appInfo) {
		Context context = createPackageContext(appInfo.packageName);
		return context.getClassLoader();
	}


	private final class NewIntentData {
		String creator;
		IBinder token;
		Intent intent;
	}
	private final class AppBindData {
		String processName;
		ApplicationInfo appInfo;
		List<ProviderInfo> providers;
		Object info;
	}

	private void sendMessage(int what, Object obj) {
		Message msg = Message.obtain();
		msg.what = what;
		msg.obj = obj;
		mH.sendMessage(msg);
	}


	public static VClientImpl getClient() {
		return gClient;
	}

	@Override
	public IBinder getAppThread() throws RemoteException {
		return ActivityThread.getApplicationThread.call(VirtualCore.mainThread());
	}

	@Override
	public IBinder getToken() {
		return token;
	}

	public void initProcess(IBinder token, int vuid) {
		if (this.token != null) {
			throw new IllegalStateException("Token is exist!");
		}
		this.token = token;
		this.vuid = vuid;
	}

	private class H extends Handler {

		private H() {
			super(Looper.getMainLooper());
		}

		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
				case NEW_INTENT: {
					handleNewIntent((NewIntentData) msg.obj);
				} break;
			}
		}
	}

	private void handleNewIntent(NewIntentData data) {
		Intent intent;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
			intent = ReferrerIntent.ctor.newInstance(data.intent, data.creator);
		} else {
			intent = data.intent;
		}
		ActivityThread.performNewIntents.call(
				VirtualCore.mainThread(),
				data.token,
				Collections.singletonList(intent)
		);
	}

	public void bindApplicationCheckThread(final ComponentInfo info) {
		if (Looper.getMainLooper() == Looper.myLooper()) {
			bindApplication(info, null);
		} else {
			final ConditionVariable lock = new ConditionVariable();
			VirtualRuntime.getUIHandler().post(new Runnable() {
				@Override
				public void run() {
					bindApplication(info, lock);
					lock.open();
				}
			});
			lock.block();
		}
	}

	private void bindApplication(ComponentInfo info, ConditionVariable lock) {
		AppBindData data = new AppBindData();
		data.appInfo = VPackageManager.get().getApplicationInfo(info.packageName, 0, getUserId(vuid));
		data.processName = info.processName;
		data.providers = VPackageManager.get().queryContentProviders(info.processName, vuid, PackageManager.GET_META_DATA);
		mBoundApplication = data;
		VirtualRuntime.setupRuntime(data.processName, data.appInfo);
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public synchronized void start() {
				new Exception().printStackTrace();
				super.start();
			}
		});
		ThreadGroup systemGroup = new ThreadGroup("va-system") {
			@Override
			public void uncaughtException(Thread t, Throwable e) {
				VLog.e(TAG, e);
				Process.killProcess(Process.myPid());
			}
		};
		ThreadGroup root = Thread.currentThread().getThreadGroup();
		while (true) {
			ThreadGroup parent = root.getParent();
			if (parent == null) {
				break;
			}
			root = parent;
		}
		try {
			Reflect.on(root).set("parent", systemGroup);
		} catch (Throwable e) {
			e.printStackTrace();
		}
		IOHook.hookNative();
		Object mainThread = VirtualCore.mainThread();
		IOHook.startDexOverride();
		Context context = createPackageContext(data.appInfo.packageName);
		mBoundApplication.info = ContextImpl.mPackageInfo.get(context);
		fixBoundApp(mBoundApplication);
		VMRuntime.setTargetSdkVersion.call(VMRuntime.getRuntime.call(), data.appInfo.targetSdkVersion);

		Application app = LoadedApk.makeApplication.call(data.info, false, null);
		mInitialApplication = app;
		mirror.android.app.ActivityThread.mInitialApplication.set(mainThread, app);
		ContextFixer.fixContext(app);
		List<ProviderInfo> providers = data.providers;
		if (providers != null) {
			installContentProviders(providers);
		}
		if (lock != null) {
			lock.open();
		}
		try {
			mInstrumentation.callApplicationOnCreate(app);
			mInitialApplication = ActivityThread.mInitialApplication.get(mainThread);
		} catch (Exception e) {
			if (!mInstrumentation.onException(app, e)) {
				throw new RuntimeException(
						"Unable to create application " + app.getClass().getName()
								+ ": " + e.toString(), e);
			}
		}
		VActivityManager.get().appDoneExecuting();
	}

	private Context createPackageContext(String packageName) {
		try {
			Context hostContext = VirtualCore.get().getContext();
			return hostContext.createPackageContext(packageName, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
		} catch (PackageManager.NameNotFoundException e) {
			throw new RuntimeException(e);
		}
	}

	private void fixBoundApp(AppBindData data) {
		Object thread = VirtualCore.mainThread();
		Object boundApp = mirror.android.app.ActivityThread.mBoundApplication.get(thread);
		mirror.android.app.ActivityThread.AppBindData.appInfo.set(boundApp, data.appInfo);
		mirror.android.app.ActivityThread.AppBindData.processName.set(boundApp, data.processName);
		mirror.android.app.ActivityThread.AppBindData.info.set(boundApp, data.info);
		mirror.android.app.ActivityThread.AppBindData.instrumentationName.set(boundApp, new ComponentName(data.appInfo.packageName, Instrumentation.class.getName()));
		// T_T   T_T   T_T   T_T   T_T   T_T   T_T   T_T
		// Gms use the {com.android.location.provider.jar}
		// T_T   T_T   T_T   T_T   T_T   T_T   T_T   T_T
		if (data.processName.equals("com.google.android.gms.persistent")) {
			File file = new File("/system/framework/com.android.location.provider.jar");
			if (file.exists()) {
				PathClassLoader parent = new PathClassLoader(file.getPath(), ClassLoader.getSystemClassLoader().getParent());
				Reflect.on(mBoundApplication.info).set("mBaseClassLoader", parent);
			}
		}
	}

	private void installContentProviders(List<ProviderInfo> providers) {
		long origId = Binder.clearCallingIdentity();
		try {
			for (ProviderInfo cpi : providers) {
				acquireProviderClient(cpi);
			}
		} finally {
			Binder.restoreCallingIdentity(origId);
		}
	}


	@Override
	public IBinder acquireProviderClient(ProviderInfo info) {
		if (!VClientImpl.getClient().isBound()) {
			VClientImpl.getClient().bindApplicationCheckThread(info);
		}
		IInterface provider = null;
		String[] authorities = sSplitAuthorityPattern.split(info.authority);
		String authority = (authorities == null || authorities.length == 0) ? info.authority : authorities[0];
		ContentResolver resolver = VirtualCore.get().getContext().getContentResolver();
		ContentProviderClient client;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
			client = resolver.acquireUnstableContentProviderClient(authority);
		} else {
			client = resolver.acquireContentProviderClient(authority);
		}
		if (client != null && !ProviderHook.class.isInstance(client)) {
			provider = mirror.android.content.ContentProviderClient.mContentProvider.get(client);
			ProviderHook.HookFetcher fetcher = ProviderHook.fetchHook(authority);
			if (fetcher != null) {
				ProviderHook hook = fetcher.fetch(false, info, provider);
				if (hook != null) {
					IInterface proxyProvider = ProviderHook.createProxy(provider, hook);
					if (proxyProvider != null) {
						mirror.android.content.ContentProviderClient.mContentProvider.set(client, provider);
						provider = proxyProvider;
					}
				}
			}
			client.release();
		}
		return provider != null ? provider.asBinder() : null;
	}


	@Override
	public void finishActivity(IBinder token) {
		VActivityManager.get().finishActivity(token);
	}

	@Override
	public void scheduleNewIntent(String creator, IBinder token, Intent intent) {
		NewIntentData data = new NewIntentData();
		data.creator = creator;
		data.token = token;
		data.intent = intent;
		sendMessage(NEW_INTENT, data);
	}


	@Override
	public boolean startActivityFromToken(IBinder token, Intent intent, int requestCode, Bundle options) {
		return VActivityManager.get().startActivityFromToken(token, intent, requestCode, options);
	}

	@Override
	public IBinder createProxyService(ComponentName component, IBinder binder) {
		return ProxyServiceFactory.getProxyService(getCurrentApplication(), component, binder);
	}


}
