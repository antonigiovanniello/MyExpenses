package org.totschnig.myexpenses.fragment

import android.app.Dialog
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.Menu
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.RecyclerView
import icepick.Icepick
import icepick.State
import org.threeten.bp.format.DateTimeFormatter
import org.totschnig.myexpenses.MyApplication
import org.totschnig.myexpenses.R
import org.totschnig.myexpenses.activity.ManageTemplates
import org.totschnig.myexpenses.activity.ProtectedFragmentActivity
import org.totschnig.myexpenses.databinding.PlanInstanceBinding
import org.totschnig.myexpenses.databinding.PlannerFragmentBinding
import org.totschnig.myexpenses.dialog.CommitSafeDialogFragment
import org.totschnig.myexpenses.model.Money
import org.totschnig.myexpenses.provider.CalendarProviderProxy.calculateId
import org.totschnig.myexpenses.provider.TransactionProvider
import org.totschnig.myexpenses.task.TaskExecutionFragment
import org.totschnig.myexpenses.util.CurrencyFormatter
import org.totschnig.myexpenses.util.UiUtils
import org.totschnig.myexpenses.util.getDateTimeFormatter
import org.totschnig.myexpenses.viewmodel.PlannerViewModel
import org.totschnig.myexpenses.viewmodel.data.PlanInstance
import org.totschnig.myexpenses.viewmodel.data.PlanInstanceState
import org.totschnig.myexpenses.viewmodel.data.PlanInstanceUpdate
import timber.log.Timber
import javax.inject.Inject

fun configureMenuInternalPlanInstances(menu: Menu, state: PlanInstanceState) {
    configureMenuInternalPlanInstances(menu, 1, state == PlanInstanceState.OPEN,
            state == PlanInstanceState.APPLIED,
            state == PlanInstanceState.CANCELLED)
}

fun configureMenuInternalPlanInstances(menu: Menu, count: Int, withOpen: Boolean,
                                       withApplied: Boolean, withCancelled: Boolean) {
    //state open
    menu.findItem(R.id.CREATE_PLAN_INSTANCE_SAVE_COMMAND).isVisible = withOpen
    menu.findItem(R.id.CREATE_PLAN_INSTANCE_EDIT_COMMAND).isVisible = count == 1 && withOpen
    //state open or applied
    menu.findItem(R.id.CANCEL_PLAN_INSTANCE_COMMAND).isVisible = withOpen || withApplied
    //state cancelled or applied
    menu.findItem(R.id.RESET_PLAN_INSTANCE_COMMAND).isVisible = withApplied || withCancelled
    //state applied
    menu.findItem(R.id.EDIT_PLAN_INSTANCE_COMMAND).isVisible = count == 1 && withApplied
}

class PlannerFragment : CommitSafeDialogFragment() {

    private var _binding: PlannerFragmentBinding? = null

    // This property is only valid between onCreateDialog and onDestroyView.
    private val binding get() = _binding!!

    val model: PlannerViewModel by viewModels()

    @State
    @JvmField
    var instanceUriToUpdate: Uri? = null

    @State
    @JvmField
    var selectedInstances: HashSet<PlanInstance> = HashSet()

    private lateinit var stateObserver: ContentObserver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Icepick.restoreInstanceState(this, savedInstanceState)
        stateObserver = StateObserver()
        context?.contentResolver?.registerContentObserver(TransactionProvider.PLAN_INSTANCE_STATUS_URI,
                true, stateObserver)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Icepick.saveInstanceState(this, outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            context?.contentResolver?.unregisterContentObserver(stateObserver)
        } catch (ise: IllegalStateException) {
            // Do Nothing.  Observer has already been unregistered.
        }
    }

    private val adapter
        get() = _binding?.recyclerView?.adapter

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = PlannerFragmentBinding.inflate(LayoutInflater.from(activity), null, false)
        val plannerAdapter = PlannerAdapter()
        binding.recyclerView.adapter = plannerAdapter
        binding.Title.movementMethod = LinkMovementMethod.getInstance()
        model.getInstances().observe(this, Observer { list ->
            val previousCount = plannerAdapter.itemCount
            plannerAdapter.addData(list)
            val itemCount = plannerAdapter.itemCount
            if (previousCount > 0 && itemCount > 0) {
                binding.recyclerView.layoutManager?.scrollToPosition(if (list.first) itemCount - 1 else 0)
            }
        })
        model.getTitle().observe(this, Observer { title ->
            binding.Title.text = title
        })
        model.getUpdates().observe(this, Observer { update ->
            //Timber.d("Update posted")
            plannerAdapter.postUpdate(update)
        })
        if (savedInstanceState == null) {
            model.loadInstances()
        }
        val alertDialog = AlertDialog.Builder(requireContext())
                .setView(binding.root)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.menu_create_instance_save, null)
                .create()
        alertDialog.setOnShowListener {
            alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { onBulkApply() }
            configureBulkApplyButton()

        }
        binding.HELPCOMMAND.setOnClickListener { view ->
            (activity as? ProtectedFragmentActivity)?.dispatchCommand(view.id,
                    ManageTemplates.HelpVariant.planner.name)
        }
        return alertDialog
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun onBulkApply() {
        (parentFragment as? TemplatesList)?.dispatchCreateInstanceSaveDo(
                selectedInstances.map { planInstance -> planInstance.templateId }.toTypedArray(),
                selectedInstances.map { planInstance -> arrayOf(calculateId(planInstance.date), planInstance.date) }.toTypedArray())
        selectedInstances.clear()
        configureBulkApplyButton()
    }

    fun onEditRequestOk() {
        instanceUriToUpdate?.let {
            model.getUpdateFor(it)
        }
    }

    inner class StateObserver : ContentObserver(Handler()) {
        override fun onChange(selfChange: Boolean, uri: Uri) {
            Timber.d("received state change for uri: %s", uri)
            model.getUpdateFor(uri)
        }
    }

    inner class PlannerAdapter : RecyclerView.Adapter<PlanInstanceViewHolder>() {
        val data = mutableListOf<PlanInstance>()
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlanInstanceViewHolder {
            val itemBinding = PlanInstanceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return PlanInstanceViewHolder(itemBinding)
        }

        override fun getItemCount(): Int {
            return data.size
        }

        fun addData(pair: Pair<Boolean, List<PlanInstance>>) {
            val (later, data) = pair
            val insertionPoint = if (later) this.data.size else 0
            this.data.addAll(insertionPoint, data)
            notifyItemRangeInserted(insertionPoint, data.size)
        }

        override fun onBindViewHolder(holder: PlanInstanceViewHolder, position: Int) {
            holder.bind(data[position], position)
        }

        fun postUpdate(update: PlanInstanceUpdate) {
            data.indexOfFirst { planInstance -> planInstance.templateId == update.templateId && calculateId(planInstance.date) == update.instanceId }
                    .takeIf { it != -1 }?.let { index ->
                        val oldInstance = data[index]
                        val amount = update.amount?.let { Money(oldInstance.amount.currencyUnit, it) }
                                ?: oldInstance.amount
                        data[index] = PlanInstance(oldInstance.templateId, update.transactionId, oldInstance.title, oldInstance.date, oldInstance.color,
                                amount, update.newState)
                        notifyItemChanged(index)
                    }
        }
    }

    inner class PlanInstanceViewHolder(private val itemBinding: PlanInstanceBinding) : RecyclerView.ViewHolder(itemBinding.root) {
        @Inject
        lateinit var currencyFormatter: CurrencyFormatter
        private val formatter: DateTimeFormatter = getDateTimeFormatter(itemBinding.root.context)

        init {
            (itemBinding.root.context.applicationContext as MyApplication).appComponent.inject(this)
        }

        fun bind(planInstance: PlanInstance, position: Int) {
            with(itemBinding) {
                root.isSelected = selectedInstances.contains(planInstance)
                date.text = planInstance.localDate.format(formatter)
                label.text = planInstance.title
                state.setImageResource(when (planInstance.state) {
                    PlanInstanceState.OPEN -> R.drawable.ic_stat_open
                    PlanInstanceState.APPLIED -> R.drawable.ic_stat_applied
                    PlanInstanceState.CANCELLED -> R.drawable.ic_stat_cancelled
                })
                colorAccount.setBackgroundColor(planInstance.color)
                amount.text = currencyFormatter.formatCurrency(planInstance.amount)
                amount.setTextColor(UiUtils.themeIntAttr(root.context,
                        if (planInstance.amount.amountMinor < 0) R.attr.colorExpense else R.attr.colorIncome))
                root.setOnLongClickListener {
                    return@setOnLongClickListener onSelection(planInstance, position)
                }
                root.setOnClickListener {
                    if (selectedInstances.size > 0) {
                        if (onSelection(planInstance, position))
                            return@setOnClickListener
                    }
                    val popup = PopupMenu(root.context, root)
                    popup.inflate(R.menu.planlist_context)
                    configureMenuInternalPlanInstances(popup.menu, planInstance.state)
                    popup.setOnMenuItemClickListener { item ->
                        val templatesList = parentFragment as? TemplatesList
                        val instanceId = calculateId(planInstance.date)
                        when (item.itemId) {
                            R.id.CREATE_PLAN_INSTANCE_EDIT_COMMAND -> {
                                templatesList?.dispatchCreateInstanceEdit(
                                        planInstance.templateId, instanceId,
                                        planInstance.date)
                                true
                            }
                            R.id.CREATE_PLAN_INSTANCE_SAVE_COMMAND -> {
                                templatesList?.dispatchCreateInstanceSaveDo(arrayOf(planInstance.templateId), arrayOf(arrayOf(instanceId, planInstance.date)))
                                true

                            }
                            R.id.EDIT_PLAN_INSTANCE_COMMAND -> {
                                instanceUriToUpdate = TransactionProvider.PLAN_INSTANCE_SINGLE_URI(planInstance.templateId, instanceId)
                                templatesList?.dispatchEditInstance(planInstance.transactionId)
                                true
                            }
                            R.id.CANCEL_PLAN_INSTANCE_COMMAND -> {
                                templatesList?.dispatchTask(TaskExecutionFragment.TASK_CANCEL_PLAN_INSTANCE, arrayOf(instanceId), arrayOf(arrayOf(planInstance.templateId, planInstance.transactionId)))
                                true
                            }
                            R.id.RESET_PLAN_INSTANCE_COMMAND -> {
                                templatesList?.dispatchTask(TaskExecutionFragment.TASK_RESET_PLAN_INSTANCE, arrayOf(instanceId), arrayOf(arrayOf(planInstance.templateId, planInstance.transactionId)))
                                true
                            }
                            else -> false
                        }
                    }
                    //displaying the popup
                    popup.show()
                }
            }
        }

        private fun onSelection(planInstance: PlanInstance, position: Int) =
                if (planInstance.state == PlanInstanceState.OPEN) {
                    if (selectedInstances.contains(planInstance)) {
                        selectedInstances.remove(planInstance)
                    } else {
                        selectedInstances.add(planInstance)
                    }
                    adapter?.notifyItemChanged(position)
                    configureBulkApplyButton()
                    true
                } else {
                    false
                }
    }

    private fun configureBulkApplyButton() {
        (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_NEUTRAL)?.let {
            val enabled = selectedInstances.size > 0
            it.isEnabled = enabled
            it.text = if (enabled) "%s (%d)".format(getString(R.string.menu_create_instance_save), selectedInstances.size) else ""
        }
    }

}