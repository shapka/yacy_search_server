// unHTML.java 
// ---------------------------
// part of YaCy
// (C) by Marc Nause; marc.nause@gmx.de.
// Braunschweig, Germany, 2005
// last major change: 27.06.2005
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//
// Using this software in any meaning (reading, learning, copying, compiling,
// running) means that you agree that the Author(s) is (are) not responsible
// for cost, loss of data or any harm that may be caused directly or indirectly
// by usage of this softare or this documentation. The usage of this software
// is on your own risk. The installation and usage (starting/running) of this
// software may allow other people or application to access your computer and
// any attached devices and is highly dependent on the configuration of the
// software which must be done by the user of the software; the author(s) is
// (are) also not responsible for proper configuration and usage of the
// software, even if provoked by documentation provided together with
// the software.
//
// Any changes to this file according to the GPL as documented in the file
// gpl.txt aside this file in the shipment you received can be done to the
// lines that follows this copyright notice here, but changes must not be
// done inside the copyright notive above. A re-distribution must contain
// the intact and unchanged copyright notice.
// Contributions and changes to the program code must be marked as such.

/**Some code to avoid people being able to mess with the message system
  *by using HTML.
  *@author Marc Nause
  extended for bbCode by Alexander Schier
  */
  
package de.anomic.tools;  
  
public class bbCode {
	
	String text;
	/**init - no Code yet*/
	public bbCode()
	{
	}

	/**Replaces all < and > with &lt; and &gt; in a string.
	*@author Marc Nause
	*@return String
	*/
	public String escapeHtml(String input){
		String output = "";
		int iter = 0;
		
		while(iter < input.length()){
			String temp = input.substring(iter,iter+1);
			iter++;
			if(temp.equals("<")) {temp = "&lt;";}
			else if (temp.equals(">")) {temp = "&gt;";}
			output = output + temp;
		}
	
	return output;
	}
	
	/**Parses parts of bbCode (will be extended).
	*@author Alexander Schier
	*@return String
	*/
	public String bb(String input){
		String output = escapeHtml(input);

		output = output.replaceAll("\\[b\\]", "<b>");
		output = output.replaceAll("\\[/b\\]", "</b>");
		return output;
	}
}
