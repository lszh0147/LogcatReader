package com.dp.logcatapp.fragments.filters

import android.arch.lifecycle.ViewModelProviders
import android.os.Bundle
import android.support.v7.widget.DividerItemDecoration
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.view.*
import android.widget.ImageButton
import android.widget.TextView
import com.dp.logcat.LogPriority
import com.dp.logcatapp.R
import com.dp.logcatapp.db.FilterInfo
import com.dp.logcatapp.db.MyDB
import com.dp.logcatapp.fragments.base.BaseFragment
import com.dp.logcatapp.fragments.filters.dialogs.FilterDialogFragment
import com.dp.logcatapp.fragments.logcatlive.LogcatLiveViewModel
import com.dp.logcatapp.util.inflateLayout
import io.reactivex.Flowable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers

class FiltersFragment : BaseFragment() {

    companion object {
        val TAG = FiltersFragment::class.qualifiedName
        private val KEY_EXCLUSIONS = TAG + "_key_exclusions"

        fun newInstance(exclusions: Boolean): FiltersFragment {
            val frag = FiltersFragment()
            val bundle = Bundle()
            bundle.putBoolean(KEY_EXCLUSIONS, exclusions)
            frag.arguments = bundle
            return frag
        }
    }

    private lateinit var viewModel: LogcatLiveViewModel
    private lateinit var recyclerViewAdapter: MyRecyclerViewAdapter
    private lateinit var linearLayoutManager: LinearLayoutManager
    private lateinit var emptyMessage: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        viewModel = ViewModelProviders.of(activity!!)
                .get(LogcatLiveViewModel::class.java)
        recyclerViewAdapter = MyRecyclerViewAdapter {
            onRemoveClicked(it)
        }

        val dao = MyDB.getInstance(activity!!).filterDao()
        val flowable = if (isExclusions()) {
            dao.getExclusions()
        } else {
            dao.getFilters()
        }

        flowable.observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    val data = it.map {
                        val displayText: String
                        val type: String
                        when (it.type) {
                            FilterType.LOG_LEVELS -> {
                                type = "Log level"
                                displayText = it.content.split(",")
                                        .joinToString(", ") {
                                            when (it) {
                                                LogPriority.ASSERT -> "Assert"
                                                LogPriority.ERROR -> "Error"
                                                LogPriority.DEBUG -> "Debug"
                                                LogPriority.FATAL -> "Fatal"
                                                LogPriority.INFO -> "Info"
                                                LogPriority.VERBOSE -> "Verbose"
                                                LogPriority.WARNING -> "warning"
                                                else -> ""
                                            }
                                        }
                            }
                            else -> {
                                displayText = it.content
                                when (it.type) {
                                    FilterType.KEYWORD -> type = "Keyword"
                                    FilterType.TAG -> type = "Tag"
                                    FilterType.PID -> type = "Pid"
                                    FilterType.TID -> type = "Tid"
                                    else -> throw IllegalStateException("invalid type: ${it.type}")
                                }
                            }
                        }
                        FilterListItem(type, displayText, it)
                    }

                    if (data.isEmpty()) {
                        emptyMessage.visibility = View.VISIBLE
                    } else {
                        emptyMessage.visibility = View.GONE
                    }
                    recyclerViewAdapter.setData(data)
                }
    }

    fun isExclusions() = arguments?.getBoolean(KEY_EXCLUSIONS) ?: false

    private fun onRemoveClicked(v: View) {
        val pos = linearLayoutManager.getPosition(v)
        if (pos != RecyclerView.NO_POSITION) {
            val item = recyclerViewAdapter[pos]
            recyclerViewAdapter.remove(pos)
            Flowable.just(MyDB.getInstance(context!!))
                    .subscribeOn(Schedulers.io())
                    .subscribe {
                        it.filterDao().delete(item.info)
                    }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        val rootView = inflateLayout(R.layout.filters_fragment)

        emptyMessage = rootView.findViewById(R.id.textViewEmpty)

        val recyclerView = rootView.findViewById<RecyclerView>(R.id.recyclerView)
        recyclerView.addItemDecoration(DividerItemDecoration(activity!!,
                DividerItemDecoration.VERTICAL))
        linearLayoutManager = LinearLayoutManager(activity!!)
        recyclerView.layoutManager = linearLayoutManager
        recyclerView.adapter = recyclerViewAdapter

        return rootView
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.filters, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.add_action -> {
                val frag = FilterDialogFragment()
                frag.setTargetFragment(this, 0)
                frag.show(fragmentManager, FilterDialogFragment.TAG)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    fun addFilter(keyword: String, tag: String, pid: String, tid: String, logLevels: Set<String>) {
        val list = mutableListOf<FilterInfo>()
        val exclude = isExclusions()

        if (keyword.isNotEmpty()) {
            list.add(FilterInfo(FilterType.KEYWORD, keyword, exclude))
        }

        if (tag.isNotEmpty()) {
            list.add(FilterInfo(FilterType.TAG, tag, exclude))
        }

        if (pid.isNotEmpty()) {
            list.add(FilterInfo(FilterType.PID, pid, exclude))
        }

        if (tid.isNotEmpty()) {
            list.add(FilterInfo(FilterType.TID, tid, exclude))
        }

        if (logLevels.isNotEmpty()) {
            list.add(FilterInfo(FilterType.LOG_LEVELS,
                    logLevels.sorted().joinToString(","), exclude))
        }

        if (list.isNotEmpty()) {
            Flowable.just(MyDB.getInstance(context!!))
                    .subscribeOn(Schedulers.io())
                    .subscribe {
                        it.filterDao().insert(*list.toTypedArray())
                    }
        }
    }
}

internal data class FilterListItem(val type: String,
                                   val content: String,
                                   val info: FilterInfo)

internal class MyRecyclerViewAdapter(private val onRemoveListener: (View) -> Unit) :
        RecyclerView.Adapter<MyRecyclerViewAdapter.MyViewHolder>() {

    private val data = mutableListOf<FilterListItem>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder {
        val view = parent.context.inflateLayout(R.layout.filters_fragment_list_item, parent)
        view.findViewById<ImageButton>(R.id.removeFilterIcon).setOnClickListener {
            onRemoveListener(it.parent as ViewGroup)
        }
        return MyViewHolder(view)
    }

    override fun getItemCount() = data.size

    operator fun get(index: Int) = data[index]

    fun remove(index: Int) {
        data.removeAt(index)
        notifyItemRemoved(index)
    }

    fun setData(data: List<FilterListItem>) {
        val size = this.data.size
        this.data.clear()
        notifyItemRangeRemoved(0, size)
        this.data.addAll(data)
        notifyItemRangeInserted(0, data.size)
    }

    override fun onBindViewHolder(holder: MyViewHolder, position: Int) {
        val item = data[position]
        holder.content.text = item.content
        holder.type.text = item.type
    }

    class MyViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val content: TextView = itemView.findViewById(R.id.content)
        val type: TextView = itemView.findViewById(R.id.type)
    }
}