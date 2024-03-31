package net.glotch.antsy

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.util.JsonWriter
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commit
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Recycler
import net.glotch.antsy.databinding.ActivityMenuBinding
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONStringer
import java.io.File
import java.io.FileOutputStream

class MenuActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMenuBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val jsonFile = File(applicationContext.filesDir, "boards.json")
        if(!jsonFile.exists()) {
            jsonFile.createNewFile()
            val emptyJson = JSONObject("{ \"boards\": [] }").toString()
            jsonFile.appendText(emptyJson)
        }

        val boardsJSON = JSONObject(jsonFile.readText())
        val boardsArray = boardsJSON.getJSONArray("boards")


     binding = ActivityMenuBinding.inflate(layoutInflater)
     setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        //val navController = findNavController(R.id.nav_host_fragment_content_menu)
        //appBarConfiguration = AppBarConfiguration(navController.graph)
        //setupActionBarWithNavController(navController, appBarConfiguration)

        val adapter = BoardListAdapter(boardsArray)
        binding.mainMenuList.layoutManager = LinearLayoutManager(applicationContext)
        binding.mainMenuList.adapter = adapter

        binding.fab.setOnClickListener { view ->
            addBoardDialog(false)
        }
    }

    private fun addBoardDialog(edit: Boolean, curName: String? = null, curAddress: String? = null, curPort: Int? = null, position: Int? = null) {
        val dialog = AlertDialog.Builder(this)
            .setView(R.layout.dialog_add_board)
            .setPositiveButton(if(!edit) "Add" else "Edit") {d, id ->
            }
            .setNegativeButton("Cancel") {_, _ -> }
            .show()
        val nameView = dialog.findViewById<EditText>(R.id.addBoardName)
        val portView = dialog.findViewById<EditText>(R.id.addBoardPort)
        val addrView = dialog.findViewById<EditText>(R.id.addBoardAddress)
        if(edit) {
            dialog.findViewById<TextView>(R.id.addBoardTitleText)?.text = "Edit Board"
            val editableFactory = Editable.Factory.getInstance()
            nameView?.text = editableFactory.newEditable(curName)
            portView?.text = editableFactory.newEditable(curPort.toString())
            addrView?.text = editableFactory.newEditable(curAddress)
        }
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { _ ->
            val name = nameView?.text.toString()
            val addr = addrView?.text.toString()
            val portStr = portView?.text.toString()
            val port = if(portStr.isEmpty()) 23 else portStr.toInt()

            var valid = true

            if(name.isEmpty()) {
                nameView?.error = "Name is required!"
                valid = false
            }
            if(port > 65535) {
                portView?.error = "Port must be between 0-65535!"
                valid = false
            }
            if(addr.isEmpty()) {
                addrView?.error = "Address is required!"
                valid = false
            }
            if(valid) {
                Log.d("antsy", "${addr}:${port}")
                val jsonString = JSONStringer().`object`()
                    .key("name").value(name)
                    .key("address").value(addr)
                    .key("port").value(port).endObject().toString()

                val jsonFile = File(applicationContext.filesDir, "boards.json")

                val boardsJSON = JSONObject(jsonFile.readText())
                val boardsArray = boardsJSON.getJSONArray("boards")

                val adapter = findViewById<RecyclerView>(R.id.mainMenuList).adapter
                if(!edit){
                    boardsArray.put(JSONObject(jsonString))
                    (adapter as BoardListAdapter).localDataSet.put(JSONObject(jsonString))
                    adapter.notifyItemInserted(boardsArray.length() - 1)
                } else {
                    if (position != null) {
                        boardsArray.put(position, JSONObject(jsonString))
                        (adapter as BoardListAdapter).localDataSet.put(position, JSONObject(jsonString))
                        adapter.notifyItemChanged(position)
                    }
                }
                boardsJSON.put("boards", boardsArray)
                jsonFile.writeBytes(boardsJSON.toString().toByteArray())
                dialog.dismiss()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
    val navController = findNavController(R.id.nav_host_fragment_content_menu)
    return navController.navigateUp(appBarConfiguration)
            || super.onSupportNavigateUp()
    }

    inner class BoardListAdapter(val localDataSet: JSONArray) : RecyclerView.Adapter<BoardListAdapter.ViewHolder>() {
        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.main_menu_list_item, parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val name = holder.itemView.findViewById<TextView>(R.id.mainMenuListTitle)
            val addrPort = holder.itemView.findViewById<TextView>(R.id.mainMenuListAddress)
            val button = holder.itemView.findViewById<ImageButton>(R.id.mainMenuListButton)
            val board = localDataSet.getJSONObject(position)
            val address = board.getString("address")
            val port = board.getInt("port")

            name.text = board.getString("name")
            addrPort.text = "${address}:${port}"

            holder.itemView.setOnClickListener {
                val mainIntent = Intent(this@MenuActivity, MainActivity::class.java)
                mainIntent.putExtra("addr", address)
                mainIntent.putExtra("port", port)
                startActivity(mainIntent)
            }

            button.setOnClickListener {
                val pos = holder.adapterPosition
                val popup = PopupMenu(this@MenuActivity, it)
                popup.menu.add("Edit").setOnMenuItemClickListener {
                    addBoardDialog(true, name.text.toString(), address, port, pos)
                    true
                }
                popup.menu.add("Delete").setOnMenuItemClickListener { _ ->
                    AlertDialog.Builder(this@MenuActivity)
                        .setTitle("Are you sure you want to delete ${name.text}?")
                        .setPositiveButton("Yes") { _, _ ->
                            localDataSet.remove(pos)
                            Log.d("antsy", pos.toString())
                            Log.d("antsy", localDataSet.toString())
                            notifyItemRemoved(pos)

                            val jsonFile = File(applicationContext.filesDir, "boards.json")
                            val boardsJSON = JSONObject(jsonFile.readText())
                            boardsJSON.put("boards", localDataSet)
                            jsonFile.writeBytes(boardsJSON.toString().toByteArray())
                        }
                        .setNegativeButton("No") {_, _ -> }
                        .show()
                    true
                }
                popup.show()
            }
        }

        override fun getItemCount(): Int {
            return localDataSet.length()
        }
    }
}