package org.totschnig.myexpenses.test.espresso;

import android.content.OperationApplicationException;
import android.os.RemoteException;
import android.support.test.espresso.Espresso;
import android.support.test.rule.ActivityTestRule;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.totschnig.myexpenses.R;
import org.totschnig.myexpenses.activity.ExpenseEdit;
import org.totschnig.myexpenses.model.Account;
import org.totschnig.myexpenses.model.AccountType;
import org.totschnig.myexpenses.model.PaymentMethod;

import java.util.Currency;

import static android.support.test.espresso.Espresso.onView;
import static android.support.test.espresso.action.ViewActions.click;
import static android.support.test.espresso.action.ViewActions.typeText;
import static android.support.test.espresso.matcher.ViewMatchers.withId;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.TestCase.assertTrue;

public class ExpenseEditFlowTest {

  @Rule
  public ActivityTestRule<ExpenseEdit> mActivityRule = new ActivityTestRule<>(ExpenseEdit.class);
  private static String accountLabel1 = "Test label 1";
  private static Account account1;
  private static Currency currency1 = Currency.getInstance("USD");
  private static PaymentMethod paymentMethod;

  @BeforeClass
  public static void fixture() {
    account1 = new Account(accountLabel1, currency1, 0, "", AccountType.CASH, Account.DEFAULT_COLOR);
    assertNotNull(account1.save());
    paymentMethod = new PaymentMethod("TEST");
    paymentMethod.setPaymentType(PaymentMethod.EXPENSE);
    paymentMethod.addAccountType(AccountType.CASH);
    assertNotNull(paymentMethod.save());
  }

  @AfterClass
  public static void tearDown() throws RemoteException, OperationApplicationException {
    Account.delete(account1.getId());
    PaymentMethod.delete(paymentMethod.getId());
  }

  /**
   * If user toggles from expense (where we have at least one payment method) to income (where there is none)
   * and then selects category, or opens calculator, and comes back, saving failed. We test here
   * the fix for this bug.
   */
  @Test
  public void testScenarioForBug5b11072e6007d59fcd92c40b() {
    onView(withId(R.id.AmountEditText)).perform(typeText(String.valueOf(10)));
    onView(withId(R.id.TaType)).perform(click());
    onView(withId(R.id.Category)).perform(click());
    Espresso.pressBack();
    onView(withId(R.id.SAVE_COMMAND)).perform(click());
    assertTrue(mActivityRule.getActivity().isFinishing());
  }
}
