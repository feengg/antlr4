package org.antlr.v4.runtime.nfa;

import org.antlr.runtime.CharStream;
import org.antlr.runtime.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** http://swtch.com/~rsc/regexp/regexp2.html */
/*
for(;;){
	switch(pc->opcode){
	case Char:
		if(*sp != pc->c)
			return 0;
		pc++;
		sp++;
		continue;
	case Match:
		return 1;
	case Jmp:
		pc = pc->x;
		continue;
	case Split:
		if(recursiveloop(pc->x, sp))
			return 1;
		pc = pc->y;
		continue;
	}
	assert(0);
	return -1;
}
 */
public class NFA {
	public byte[] code;
	Map<String, Integer> ruleToAddr;

	public NFA(byte[] code, Map<String, Integer> ruleToAddr) {
		this.code = code;
		this.ruleToAddr = ruleToAddr;
	}

	public int exec(CharStream input, String ruleName) {
		return exec(input, ruleToAddr.get(ruleName));
	}

	public int exec(CharStream input) { return exec(input, 0); }

	public int exec(CharStream input, int ip) {
		while ( ip < code.length ) {
			int c = input.LA(1);
			trace(ip);
			short opcode = code[ip];
			ip++; // move to next instruction or first byte of operand
			switch (opcode) {
				case Bytecode.MATCH8 :
					if ( c != code[ip] ) return 0;
					ip++;
					input.consume();
					break;
				case Bytecode.MATCH16 :
					if ( c != getShort(code, ip) ) return 0;
					ip += 2;
					input.consume();
					break;
				case Bytecode.RANGE8 :
					if ( c<code[ip] || c>code[ip+1] ) return 0;
					ip += 2;
					input.consume();
					break;
				case Bytecode.RANGE16 :
					if ( c<getShort(code, ip) || c>getShort(code, ip+2) ) return 0;
					ip += 4;
					input.consume();
					break;
				case Bytecode.ACCEPT :
					int ruleIndex = getShort(code, ip);
					ip += 2;
					System.out.println("accept "+ruleIndex);
					return ruleIndex;
				case Bytecode.JMP :
					int target = getShort(code, ip);
					ip = target;
					continue;
				case Bytecode.SPLIT :
					int nopnds = getShort(code, ip);
					ip += 2;
					for (int i=1; i<=nopnds-1; i++) {
						int addr = getShort(code, ip);
						ip += 2;
						//System.out.println("try alt "+i+" at "+addr);
						int m = input.mark();
						int r = exec(input, addr);
						if ( r>0 ) { input.release(m); return r; }
						input.rewind(m);
					}
					// try final alternative (w/o recursion)
					int addr = getShort(code, ip);
					ip = addr;
					//System.out.println("try alt "+nopnds+" at "+addr);
					continue;
				default :
					throw new RuntimeException("invalid instruction @ "+ip+": "+opcode);
			}
		}
		return 0;
	}

	public static class Context {
		public int ip;
		public int inputMarker;
		public Context(int ip, int inputMarker) {
			this.ip = ip;
			this.inputMarker = inputMarker;
		}
	}

	public int execNoRecursion(CharStream input, int ip) {
		List<Context> work = new ArrayList<Context>();
		work.add(new Context(ip, input.mark()));
workLoop:
		while ( work.size()>0 ) {
			Context ctx = work.remove(work.size()-1); // treat like stack
			ip = ctx.ip;
			input.rewind(ctx.inputMarker);
			while ( ip < code.length ) {
				int c = input.LA(1);
				trace(ip);
				short opcode = code[ip];
				ip++; // move to next instruction or first byte of operand
				switch (opcode) {
					case Bytecode.MATCH8 :
						if ( c != code[ip] ) continue workLoop;
						ip++;
						input.consume();
						break;
					case Bytecode.MATCH16 :
						if ( c != getShort(code, ip) ) continue workLoop;
						ip += 2;
						input.consume();
						break;
					case Bytecode.RANGE8 :
						if ( c<code[ip] || c>code[ip+1] ) continue workLoop;
						ip += 2;
						input.consume();
						break;
					case Bytecode.RANGE16 :
						if ( c<getShort(code, ip) || c>getShort(code, ip+2) ) continue workLoop;
						ip += 4;
						input.consume();
						break;
					case Bytecode.ACCEPT :
						int ruleIndex = getShort(code, ip);
						ip += 2;
						System.out.println("accept "+ruleIndex);
						// returning gives first match not longest; i.e., like PEG
						return ruleIndex;
					case Bytecode.JMP :
						int target = getShort(code, ip);
						ip = target;
						continue;
					case Bytecode.SPLIT :
						int nopnds = getShort(code, ip);
						ip += 2;
						// add split addresses to work queue in reverse order ('cept first one)
						for (int i=nopnds-1; i>=1; i--) {
							int addr = getShort(code, ip+i*2);
							//System.out.println("try alt "+i+" at "+addr);
							work.add(new Context(addr, input.mark()));
						}
						// try first alternative (w/o adding to work list)
						int addr = getShort(code, ip);
						ip = addr;
						//System.out.println("try alt "+nopnds+" at "+addr);
						continue;
					default :
						throw new RuntimeException("invalid instruction @ "+ip+": "+opcode);
				}
			}
		}
		return 0;
	}

	public int execThompson(CharStream input, int ip) {
		int c = input.LA(1);
		if ( c==Token.EOF ) return Token.EOF;
		
		List<Integer> closure = new ArrayList<Integer>();
		List<Integer> reach = new ArrayList<Integer>();
		int prevAcceptAddr = Integer.MAX_VALUE;
		int prevAcceptLastCharIndex = -1;
		addToClosure(closure, ip);
		do { // while more work
			c = input.LA(1);
processOneChar:
			for (int i=0; i<closure.size(); i++) {
				System.out.println("input["+input.index()+"]=="+(char)c+" closure="+closure+", i="+i+", reach="+ reach);
				ip = closure.get(i); 
				trace(ip);
				short opcode = code[ip];
				ip++; // move to next instruction or first byte of operand
				switch (opcode) {
					case Bytecode.MATCH8 :
						if ( c == code[ip] ) {
							addToClosure(reach, ip+1);
						}
						break;
					case Bytecode.MATCH16 :
						if ( c == getShort(code, ip) ) {
							addToClosure(reach, ip+2);
						}
						break;
					case Bytecode.RANGE8 :
						if ( c>=code[ip] && c<=code[ip+1] ) {
							addToClosure(reach, ip+2);
						}
						break;
					case Bytecode.RANGE16 :
						if ( c<getShort(code, ip) || c>getShort(code, ip+2) ) {
							addToClosure(reach, ip+4);
						}
						break;
					case Bytecode.ACCEPT :
						int ttype = getShort(code, ip);
						int tokenLastCharIndex = input.index() - 1;
						System.out.println("ACCEPT "+ttype+" with last char position "+ tokenLastCharIndex);
						if ( tokenLastCharIndex > prevAcceptLastCharIndex ) {
							prevAcceptLastCharIndex = tokenLastCharIndex;
							// choose longest match so far regardless of rule priority
							System.out.println("replacing old best match @ "+prevAcceptAddr);
							prevAcceptAddr = ip-1;
						}
						else if ( tokenLastCharIndex == prevAcceptLastCharIndex ) {
							// choose first rule matched if match is of same length
							if ( ip-1 < prevAcceptAddr ) { // it will see both accepts for ambig rules
								System.out.println("replacing old best match @ "+prevAcceptAddr);
								prevAcceptAddr = ip-1;
							}
						}
						// keep trying for more to get longest match
						break;
					case Bytecode.JMP :
					case Bytecode.SPLIT :
						break;
					default :
						throw new RuntimeException("invalid instruction @ "+ip+": "+opcode);
				}
			}
			if ( reach.size()>0 ) { // if we reached other states, consume and process them
				System.out.println("CONSUME");
				input.consume();
			}
			// swap to avoid reallocating space
			List<Integer> tmp = reach;
			reach = closure;
			closure = tmp;
			reach.clear();
		} while ( closure.size()>0 );

		if ( prevAcceptAddr<0 ) return Token.INVALID_TOKEN_TYPE;
		int ttype = getShort(code, prevAcceptAddr+1);
		return ttype;
	}

	void addToClosure(List<Integer> closure, int ip) {
		//System.out.println("add to closure "+ip+" "+closure);
		if ( closure.contains(ip) ) return;
		closure.add(ip);
		short opcode = code[ip];
		ip++; // move to next instruction or first byte of operand
		switch (opcode) {
			case Bytecode.JMP :
				addToClosure(closure, getShort(code, ip));
				break;
			case Bytecode.SPLIT :
				int nopnds = getShort(code, ip);
				ip += 2;
				// add split addresses to work queue in reverse order ('cept first one)
				for (int i=0; i<nopnds; i++) {
					addToClosure(closure, getShort(code, ip+i*2));
				}
				break;
		}
	}

	void trace(int ip) {
		String instr = Bytecode.disassembleInstruction(code, ip);
		System.out.println(instr);
	}

	public static int getShort(byte[] memory, int index) {
		return (memory[index]&0xFF) <<(8*1) | (memory[index+1]&0xFF); // prevent sign extension with mask
	}
}
