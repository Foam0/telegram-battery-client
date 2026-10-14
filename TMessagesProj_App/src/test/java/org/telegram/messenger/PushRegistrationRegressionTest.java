package org.telegram.messenger;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import android.app.Application;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.telegram.tgnet.ConnectionsManager;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=30,shadows=PushRegistrationRegressionTest.ImmediateQueue.class)
public class PushRegistrationRegressionTest {
    // Control the dispatch boundary so native transport and UI mocks stay on
    // the test thread. The production token/account selection code still runs.
    @Implements(DispatchQueue.class)
    public static class ImmediateQueue {
        @Implementation public void __constructor__(String name) {}
        @Implementation public void __constructor__(String name,boolean start) {}
        @Implementation public void __constructor__(String name,boolean start,int priority) {}
        @Implementation public boolean postRunnable(Runnable r){r.run();return true;}
        @Implementation public boolean postRunnable(Runnable r,long delay){r.run();return true;}
    }
    @Before public void context(){ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();}
    private void verifyRegistrations(boolean newToken,boolean changedType,int missingAccount)throws Exception{
        UserConfig[] accounts=new UserConfig[32];MessagesController[] controllers=new MessagesController[32];
        SharedConfig.pushString="cached";SharedConfig.pushType=PushListenerController.PUSH_TYPE_FIREBASE;
        SharedConfig.pushStringGetTimeStart=0;SharedConfig.pushStringGetTimeEnd=0;
        try(MockedStatic<UserConfig> users=mockStatic(UserConfig.class);
            MockedStatic<MessagesController> messages=mockStatic(MessagesController.class);
            MockedStatic<ConnectionsManager> transport=mockStatic(ConnectionsManager.class);
            MockedStatic<AndroidUtilities> ui=mockStatic(AndroidUtilities.class);
            MockedStatic<SharedConfig> config=mockStatic(SharedConfig.class)){
            ui.when(()->AndroidUtilities.runOnUIThread(any(Runnable.class))).thenAnswer(call->{((Runnable)call.getArgument(0)).run();return null;});
            for(int a=0;a<32;a++){
                final int index=a;accounts[a]=mock(UserConfig.class);accounts[a].registeredForPush=a!=missingAccount;
                when(accounts[a].getClientUserId()).thenReturn(1000L+a);
                controllers[a]=mock(MessagesController.class);
                users.when(()->UserConfig.getInstance(index)).thenReturn(accounts[a]);
                messages.when(()->MessagesController.getInstance(index)).thenReturn(controllers[a]);
            }
            int type=changedType?PushListenerController.PUSH_TYPE_WEB:PushListenerController.PUSH_TYPE_FIREBASE;
            String token=newToken?"new-token":"cached";
            PushListenerController.sendRegistrationToServer(type,token);
            for(int a=0;a<32;a++){
                boolean expected=newToken||changedType||a==missingAccount;
                verify(controllers[a],times(expected?1:0)).registerForPush(type,token);
                if(!newToken&&!changedType)assertEquals(a!=missingAccount,accounts[a].registeredForPush);
            }
            assertEquals(token,SharedConfig.pushString);assertEquals(type,SharedConfig.pushType);
        }
    }
    @Test public void cachedTokenDoesNotFanOutToThirtyTwoAccounts()throws Exception{verifyRegistrations(false,false,-1);}
    @Test public void cachedTokenRepairsOnlyTheMissingRegistration()throws Exception{verifyRegistrations(false,false,31);}
    @Test public void newTokenRegistersEveryAccountThroughItsOwnController()throws Exception{verifyRegistrations(true,false,-1);}
    @Test public void changedProviderRegistersEveryAccount()throws Exception{verifyRegistrations(false,true,-1);}
}
